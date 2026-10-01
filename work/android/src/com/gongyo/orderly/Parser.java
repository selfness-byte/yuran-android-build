package com.gongyo.orderly;

import com.gongyo.photo.PhotoContract;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative offline parser. Any unconsumed non-separator makes the result invalid. */
public final class Parser {
    public static final String MISSING_COLOR="미지정";
    private static final Pattern HEADING = Pattern.compile("(?i)(?:SKU|货号|款号|品番|품번)(?:\\s*[:=]\\s*|\\s+)([A-Z0-9][A-Z0-9._/-]*)");
    private static final Pattern SIZE = Pattern.compile("(?i)(XL|S|M|L)\\s*[:=]?\\s*([+-]?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern EMPTY_SIZE = Pattern.compile("(?i)(XL|S|M|L)(?:\\s*[:=])?(?=$|[\\s,;|])");
    private static final Pattern UNSUPPORTED_SIZE = Pattern.compile("(?i)(?:XXXL|XXL|XXXS|XXS|XS|[2-9]XL)\\s*[:=]?\\s*[+-]?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern SKU = Pattern.compile("[A-Za-z][A-Za-z0-9._/-]*");
    private static final Pattern CODE = Pattern.compile("(?i)(WH|BK|CC|LB|LE)(?=$|[\\s,;|]|(?:XL|S|M|L)\\s*[:=]?\\s*[0-9])");
    private Parser() {}

    public static Inventory.ParseResult parse(String source, Map<String,String> global,
            Map<String,Map<String,String>> bySku) {
        Inventory.ParseResult result = new Inventory.ParseResult();
        if (source == null || source.trim().isEmpty()) {
            result.errors.add("붙여 넣은 주문이 비어 있습니다."); return result;
        }
        if (source.length() > Inventory.MAX_SOURCE_CHARS) {
            result.errors.add("한 번에 200,000자까지 인식할 수 있습니다. 주문을 나누어 주세요."); return result;
        }
        String text = normalize(source);
        if (isTable(text)) { parseTable(text, global, bySku, result); return result; }
        Scan scan = new Scan(text, global, bySku, result);
        scan.run();
        return result;
    }

    /** SKU-specific rule wins. Unknown colors are preserved for the UI to require confirmation. */
    public static String mappedColor(String sku, String sourceColor, Map<String,String> global,
            Map<String,Map<String,String>> bySku) {
        String key = sourceColor == null ? "" : sourceColor.trim();
        Map<String,String> local = null;
        if(bySku!=null)for(Map.Entry<String,Map<String,String>> entry:bySku.entrySet())
            if(PhotoContract.sameKey(entry.getKey(),sku)){local=entry.getValue();break;}
        String value = ruleValue(local,key);
        if (value == null || value.trim().isEmpty()) value = ruleValue(global,key);
        if (value != null && !value.trim().isEmpty()) return value.trim().toUpperCase(Locale.ROOT);
        if (key.matches("(?i)WH|BK|CC|LB|LE")) return key.toUpperCase(Locale.ROOT);
        return key;
    }

    private static String ruleValue(Map<String,String> rules,String color){
        if(rules==null)return null;
        String exact=rules.get(color);if(exact!=null)return exact;
        for(Map.Entry<String,String> entry:rules.entrySet())
            if(PhotoContract.sameKey(entry.getKey(),color))return entry.getValue();
        return null;
    }

    private static String normalize(String source) {
        StringBuilder text = new StringBuilder(source.length());
        for (int i=0; i<source.length(); i++) {
            char c=source.charAt(i);
            if (c >= '\uff01' && c <= '\uff5e') c=(char)(c-0xfee0);
            if (c == '\u3000' || c == '\u00a0') c=' ';
            if (c == '\uff0c' || c == '\u3001') c=',';
            if (c == '\uff1b') c=';';
            if (c == '\ufeff') c=' ';
            text.append(c);
        }
        return text.toString();
    }

    private static boolean han(char c) {
        return (c >= '\u3400' && c <= '\u9fff') || (c >= '\uf900' && c <= '\ufaff');
    }
    private static boolean separator(char c) { return Character.isWhitespace(c) || c==',' || c==';' || c=='|'; }
    private static Matcher at(Pattern pattern, String text, int position) {
        Matcher matcher=pattern.matcher(text); matcher.region(position,text.length());
        return matcher.lookingAt() ? matcher : null;
    }
    private static int sizeIndex(String size) {
        String key=size.toUpperCase(Locale.ROOT);
        if ("S".equals(key)) return 0;
        if ("M".equals(key)) return 1;
        if ("L".equals(key)) return 2;
        return 3;
    }
    private static boolean knownColor(String color) { return color.matches("[A-Z][A-Z0-9_-]{0,15}"); }
    private static void finishRow(Inventory.Row row, boolean hasSize, Inventory.ParseResult result) {
        if (row == null) return;
        if (row.sku.length()>80 || row.sourceColor.length()>60 || row.color.length()>30) {
            result.errors.add(row.sourceLine+"행: 품번은 80자, 원문 색상은 60자, 출력 색상은 30자까지 지원합니다."); return;
        }
        if (!hasSize) result.warnings.add(row.sourceLine+"행: "+row.sku+" "+row.sourceColor+"에 수량이 없어 0으로 보관했습니다.");
        else if (row.total()==0) result.warnings.add(row.sourceLine+"행: "+row.sku+" "+row.sourceColor+"은 수량 0으로 보관했습니다.");
        if (result.rows.size() >= Inventory.MAX_ROWS) { result.errors.add("한 작업은 2,000행까지 지원합니다. 주문을 나누어 주세요."); return; }
        result.rows.add(row);
        if (!knownColor(row.color)) result.warnings.add(row.sourceLine+"행: "+row.sku+" ‘"+row.sourceColor+"’의 색상 코드를 직접 지정해 주세요.");
    }

    private static final class Scan {
        final String text;
        final Map<String,String> global;
        final Map<String,Map<String,String>> bySku;
        final Inventory.ParseResult result;
        int pos, line=1, skuLine=1, attemptedRows;
        String sku="";
        boolean colorSinceSku;
        Inventory.Row row;
        boolean[] assigned=new boolean[4];
        boolean hasSize;
        Scan(String text,Map<String,String> global,Map<String,Map<String,String>> bySku,Inventory.ParseResult result) {
            this.text=text;this.global=global;this.bySku=bySku;this.result=result;
        }
        void advance(int end) { while(pos<end) { if(text.charAt(pos)=='\n') line++; pos++; } }
        void error(String message) { result.errors.add(line+"행: "+message); }
        void finish() { finishRow(row,hasSize,result); row=null; assigned=new boolean[4];hasSize=false; }
        void missingColorRow(){
            row=new Inventory.Row();row.sku=sku;row.sourceLine=skuLine;row.sourceColor=MISSING_COLOR;row.color=MISSING_COLOR;colorSinceSku=true;
        }
        void finishSku(){if(!sku.isEmpty()&&!colorSinceSku)missingColorRow();finish();}
        void startSku(String value) {
            finishSku();
            sku=PhotoContract.key(value);skuLine=line;colorSinceSku=false;
        }
        void color(String value) {
            finish(); attemptedRows++;
            if(sku.isEmpty()) error("색상 앞에 SKU(품번)가 필요합니다: "+value);
            row=new Inventory.Row();row.sku=sku;row.sourceColor=value;row.sourceLine=line;
            row.color=mappedColor(sku,value,global,bySku);colorSinceSku=true;
        }
        void run() {
            while(pos<text.length()) {
                char c=text.charAt(pos);
                if(separator(c)) { advance(pos+1);continue; }
                Matcher match=at(HEADING,text,pos);
                if(match!=null) { startSku(match.group(1));advance(match.end());continue; }
                match=at(UNSUPPORTED_SIZE,text,pos);
                if(match!=null) { error("지원하지 않는 사이즈입니다: "+match.group()+" (S/M/L/XL만 지원)");advance(match.end());continue; }
                match=at(SIZE,text,pos);
                if(match!=null) {
                    int end=match.end();String raw=match.group(2);int index=sizeIndex(match.group(1));
                    boolean badTail=end<text.length() && !separator(text.charAt(end)) && !han(text.charAt(end)) && at(SIZE,text,end)==null;
                    if(badTail) {
                        while(end<text.length()&&!separator(text.charAt(end))&&!han(text.charAt(end)))end++;
                        error("수량 뒤에 알 수 없는 문자가 있습니다: "+text.substring(pos,end));
                    } else if(row==null&&sku.isEmpty()) error("사이즈 수량 앞에 SKU가 필요합니다: "+match.group());
                    else if(!raw.matches("[0-9]+")) error("수량은 0 이상의 정수만 가능합니다: "+raw);
                    else if(assigned[index]) error(row.sku+" "+row.sourceColor+"에 "+Inventory.SIZES[index]+" 수량이 두 번 있습니다.");
                    else {
                        if(row==null)missingColorRow();
                        try {
                            long q=Long.parseLong(raw);
                            if(q>Inventory.MAX_QUANTITY) error("수량은 999,999 이하만 가능합니다: "+raw);
                            else { row.quantities[index]=q;assigned[index]=true;hasSize=true; }
                        } catch(NumberFormatException ex) { error("수량이 너무 큽니다: "+raw); }
                    }
                    advance(end);continue;
                }
                match=at(EMPTY_SIZE,text,pos);
                if(match!=null){
                    int index=sizeIndex(match.group(1));
                    if(sku.isEmpty())error("사이즈 앞에 SKU가 필요합니다: "+match.group());
                    else{if(row==null)missingColorRow();if(assigned[index])error(row.sku+" "+row.sourceColor+"에 "+Inventory.SIZES[index]+" 수량이 두 번 있습니다.");else{assigned[index]=true;hasSize=true;result.warnings.add(line+"행: "+Inventory.SIZES[index]+" 수량이 없어 0으로 보관했습니다.");}}
                    advance(match.end());continue;
                }
                if(han(c)) {
                    int end=pos+1;while(end<text.length()&&han(text.charAt(end)))end++;
                    color(text.substring(pos,end));advance(end);
                    if(attemptedRows>Inventory.MAX_ROWS) { error("한 작업은 2,000행까지 지원합니다. 주문을 나누어 주세요.");break; }
                    continue;
                }
                match=at(CODE,text,pos);
                if(match!=null) { color(match.group(1).toUpperCase(Locale.ROOT));advance(match.end());continue; }
                match=at(SKU,text,pos);
                if(match!=null && match.group().matches(".*[0-9].*")) {
                    startSku(match.group());advance(match.end());continue;
                }
                int end=pos+1;
                while(end<text.length()&&!separator(text.charAt(end))&&!han(text.charAt(end)))end++;
                error("인식하지 못한 내용입니다: ‘"+text.substring(pos,end)+"’. 수량이 빠지지 않도록 원문을 확인해 주세요.");
                advance(end);
                if(result.errors.size()>=100) { error("오류가 많아 인식을 중단했습니다. 원문을 나누어 확인해 주세요.");break; }
            }
            finishSku();
            if(result.rows.isEmpty()&&result.errors.isEmpty()&&result.warnings.isEmpty()) result.errors.add("인식할 수 있는 SKU·색상·수량이 없습니다.");
        }
    }

    private static String firstNonEmpty(String text) { for(String line:text.split("\\r?\\n",-1))if(!line.trim().isEmpty())return line.trim();return ""; }
    private static String[] fields(String line,String header) {
        String[] values;
        if(header.indexOf('\t')>=0) values=line.split("\\t",-1);
        else if(header.indexOf(',')>=0) values=line.split(",",-1);
        else if(header.indexOf('|')>=0) values=line.split("\\|",-1);
        else values=line.trim().split("\\s+");
        for(int i=0;i<values.length;i++)values[i]=values[i].trim();
        return values;
    }
    private static boolean isTable(String text) {
        String header=firstNonEmpty(text);String[] f=fields(header,header);
        return f.length>=2 && f[0].matches("(?i)SKU|货号|款号|품번") && f[1].matches("(?i)COLOR|颜色|色|색상");
    }
    private static void parseTable(String text,Map<String,String> global,Map<String,Map<String,String>> bySku,Inventory.ParseResult result) {
        String header=firstNonEmpty(text);String[] columns=fields(header,header);
        if(columns.length<6 || columns.length>7 || !"S".equalsIgnoreCase(columns[2]) || !"M".equalsIgnoreCase(columns[3]) || !"L".equalsIgnoreCase(columns[4]) || !"XL".equalsIgnoreCase(columns[5])) {
            result.errors.add("표의 열은 SKU COLOR S M L XL 순서여야 합니다. 마지막 TOTAL(합계) 열은 선택입니다.");return;
        }
        if(columns.length==7&&!columns[6].matches("(?i)TOTAL|合计|总计|합계")) { result.errors.add("마지막 표 열은 TOTAL(합계)이어야 합니다.");return; }
        String[] lines=text.split("\\r?\\n",-1);boolean passedHeader=false;String lastSku="";int rowCount=0;
        for(int n=0;n<lines.length;n++) {
            if(lines[n].trim().isEmpty())continue;
            if(!passedHeader){passedHeader=true;continue;}
            if(++rowCount>Inventory.MAX_ROWS){result.errors.add("한 작업은 2,000행까지 지원합니다.");break;}
            String[] values=fields(lines[n],header);
            if(values.length!=columns.length){result.errors.add((n+1)+"행: 표의 열 개수가 머리글과 다릅니다.");continue;}
            Inventory.Row row=new Inventory.Row();row.sourceLine=n+1;row.sku=values[0].isEmpty()?lastSku:PhotoContract.key(values[0]);row.sourceColor=values[1];
            if(row.sku.isEmpty()){result.errors.add((n+1)+"행: SKU가 필요합니다.");continue;}
            if(row.sourceColor.isEmpty())row.sourceColor=MISSING_COLOR;
            lastSku=row.sku;row.color=mappedColor(row.sku,row.sourceColor,global,bySku);boolean ok=true;
            for(int i=0;i<4;i++) {
                String value=values[i+2];
                if(value.isEmpty())value="0";
                try {
                    if(!value.matches("[0-9]+"))throw new NumberFormatException();
                    long q=Long.parseLong(value);if(q>Inventory.MAX_QUANTITY)throw new NumberFormatException();row.quantities[i]=q;
                } catch(NumberFormatException ex){result.errors.add((n+1)+"행: "+Inventory.SIZES[i]+" 수량은 0~999,999 정수여야 합니다.");ok=false;}
            }
            if(columns.length==7) {
                try { if(!values[6].matches("[0-9]+")||Long.parseLong(values[6])!=row.total())throw new NumberFormatException(); }
                catch(NumberFormatException ex){result.errors.add((n+1)+"행: 원문의 합계가 사이즈별 합계와 일치하지 않습니다.");ok=false;}
            }
            if(ok)finishRow(row,true,result);
        }
        if(rowCount==0)result.errors.add("표 머리글 아래에 주문 행을 붙여 넣어 주세요.");
    }
}
