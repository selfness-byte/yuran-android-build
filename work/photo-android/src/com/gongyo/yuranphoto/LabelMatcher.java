package com.gongyo.yuranphoto;

import android.os.Bundle;
import com.gongyo.photo.PhotoContract;
import com.gongyo.photo.IntakeText;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only label suggestions. Catalogue identity and decoded barcodes remain owner-authoritative. */
public final class LabelMatcher {
    private static final int MAX_CATALOG=20000, MAX_MATCHES=40, MAX_TEXT=12000;
    private static final Pattern SIZE=Pattern.compile("(?:[0-9]{1,3})?(?:XXXL|XXL|XL|XS|S|M|L)|[0-9]{2,3}");
    private static final Pattern STYLE=Pattern.compile("EY[0-9]UTS[0-9]{3,6}(?:R[0-9]{1,3})?");
    private static final Pattern EXPLICIT_SKU=IntakeText.SKU;
    private static final Pattern EXPLICIT_COLOR=IntakeText.COLOR;
    private static final Pattern EXPLICIT_SIZE=IntakeText.GARMENT_SIZE;
    private LabelMatcher() {}

    public static Bundle match(PhotoClient client,String text,ArrayList<String> barcodes)throws IOException {
        if(client==null)throw new IOException("유란노트 연결을 먼저 확인해 주세요.");
        String original=text==null?"":text;
        if(original.length()>MAX_TEXT)throw new IOException("읽힌 글자가 너무 많습니다. 라벨 한 장만 가까이 촬영해 주세요.");
        ArrayList<String> decoded=barcodes==null?new ArrayList<String>():new ArrayList<String>(barcodes);
        if(decoded.size()>20)throw new IOException("바코드가 너무 많습니다. 라벨 한 장만 촬영해 주세요.");
        for(int i=0;i<decoded.size();i++)decoded.set(i,PhotoClient.barcodeText(decoded.get(i)));
        if(original.trim().isEmpty()&&decoded.isEmpty())throw new IOException("라벨 글자와 바코드를 읽지 못했습니다. 초점을 맞춰 다시 촬영하거나 품번을 직접 입력해 주세요.");
        PhotoContract.Compatibility token=client.compatibility();
        if(token.noteVersionCode<23)throw new IOException("사진 구별에는 유란노트 1.17.1 이상이 필요합니다.");
        LinkedHashMap<String,Bundle> catalogue=readCatalogue(client,token);
        LinkedHashMap<String,List<Bundle>> bySku=new LinkedHashMap<String,List<Bundle>>();
        for(Bundle row:catalogue.values()) {
            String sku=PhotoContract.key(row.getString("sku"));
            List<Bundle> variants=bySku.get(sku);if(variants==null){variants=new ArrayList<Bundle>();bySku.put(sku,variants);}variants.add(row);
        }
        LinkedHashSet<String> warnings=new LinkedHashSet<String>();
        LinkedHashMap<String,Bundle> candidates=new LinkedHashMap<String,Bundle>();
        LinkedHashSet<String> barcodeKeys=new LinkedHashSet<String>();
        String newBarcode="";
        for(String barcode:new LinkedHashSet<String>(decoded)) {
            Bundle found=client.lookupBarcode(barcode);requireToken(found,token);
            String status=found.getString("status","");
            if(PhotoContract.NOT_FOUND.equals(status)) {if(decoded.size()==1)newBarcode=barcode;continue;}
            if(!PhotoContract.OK.equals(status))throw backend("바코드 조회",found);
            String identity=identity(found);Bundle row=catalogue.get(identity);
            if(row==null||!sameField(row,found,"productId")||!sameField(row,found,"variantId"))throw stale();
            barcodeKeys.add(identity);
            addCandidate(candidates,row,"실제로 읽은 바코드의 등록 항목",decoded.size()==1?barcode:"");
        }
        if(decoded.size()>1)warnings.add("바코드가 여러 개 읽혔습니다. 특정 항목에 자동으로 넘기지 않으므로 필요한 바코드를 한 개씩 다시 촬영해 주세요.");

        Ocr ocr=new Ocr(original);
        List<SkuHit> hits=findSkus(ocr,bySku);
        LinkedHashMap<String,List<SkuHit>> skuHits=new LinkedHashMap<String,List<SkuHit>>();
        for(SkuHit hit:hits){List<SkuHit> group=skuHits.get(hit.sku);if(group==null){group=new ArrayList<SkuHit>();skuHits.put(hit.sku,group);}group.add(hit);}
        LinkedHashSet<String> ocrKeys=new LinkedHashSet<String>(),readColors=new LinkedHashSet<String>(),sizes=new LinkedHashSet<String>();
        String readSku=skuHits.size()==1?skuHits.keySet().iterator().next():"";
        if(skuHits.size()>1)warnings.add("여러 품번이 읽혔습니다. 후보의 품번과 색상을 확인해 선택해 주세요.");
        for(Map.Entry<String,List<SkuHit>> entry:skuHits.entrySet()) {
            List<Bundle> variants=bySku.get(entry.getKey());
            List<AliasHit> colors=new ArrayList<AliasHit>();
            for(Bundle row:variants)for(String alias:aliases(row)) {
                String needle=flat(alias);if(needle.isEmpty())continue;
                int at=-1;
                while((at=ocr.flat.indexOf(needle,at+1))>=0) {
                    int start=ocr.positions.get(at),end=ocr.positions.get(at+needle.length()-1)+1;
                    if(IntakeText.isGarmentSpan(ocr.text,start,end)||!inSkuScope(start,entry.getValue(),hits,skuHits.size()==1))continue;
                    boolean followsSku=false;for(SkuHit hit:entry.getValue())if(hit.end==start)followsSku=true;
                    if(!leftBoundary(ocr.text,start)&&!followsSku&&!afterMarker(ocr.text,start,"颜色","顏色","色号","色號","COLOR","COLOUR"))continue;
                    String tail=tail(ocr.text,end);
                    if(!rightBoundary(ocr.text,end)&&!isSize(tail))continue;
                    colors.add(new AliasHit(row,start,end,needle.length(),isSize(tail)?tail:""));
                }
            }
            Set<String> selected=new LinkedHashSet<String>();
            for(AliasHit color:colors) {
                boolean contained=false;
                for(AliasHit other:colors)if(other!=color&&other.start<=color.start&&other.end>=color.end&&other.length>color.length){contained=true;break;}
                if(!contained){selected.add(identity(color.row));readColors.add(PhotoContract.key(color.row.getString("sourceColor")));if(!color.size.isEmpty())sizes.add(color.size);}
            }
            if(selected.isEmpty())warnings.add(entry.getKey()+"의 색상을 확실히 읽지 못해 등록된 색상 후보를 모두 표시합니다.");
            else if(selected.size()>1)warnings.add(entry.getKey()+"에서 여러 색상 후보가 읽혔습니다. 라벨의 색상을 확인해 주세요.");
            for(Bundle row:variants)if(selected.isEmpty()||selected.contains(identity(row))) {
                String key=identity(row);ocrKeys.add(key);
                addCandidate(candidates,row,selected.isEmpty()?"품번 OCR 일치 · 색상 확인 필요":"품번 OCR 및 등록된 색상·별칭 일치",newBarcode);
            }
        }
        String suggestedColor="";
        if(skuHits.isEmpty()&&!ocr.text.trim().isEmpty()) {
            LinkedHashSet<String> suggestions=suggestSkus(ocr);
            if(suggestions.size()==1)readSku=suggestions.iterator().next();
            else if(suggestions.size()>1)warnings.add("등록되지 않은 품번 형태가 여러 개 읽혔습니다. 새 등록 시 품번을 직접 확인해 주세요.");
            if(!readSku.isEmpty()) {
                warnings.add("읽힌 품번과 일치하는 등록 품번을 찾지 못했습니다. OCR의 문자와 숫자를 원본 라벨에서 확인한 뒤 수정해 주세요.");
                suggestedColor=uniqueField(EXPLICIT_COLOR,ocr.text);
                // A conservative EY style can carry literal color/size suffixes; these are never mappings.
                String compactSku=flat(readSku);int p=ocr.flat.indexOf(compactSku);
                if(p>=0){int end=ocr.positions.get(p+compactSku.length()-1)+1;Matcher suffix=Pattern.compile("([A-Z]{1,4})([0-9]{1,3}(?:XXXL|XXL|XL|XS|S|M|L))").matcher(tail(ocr.text,end));if(suffix.matches()){if(suggestedColor.isEmpty())suggestedColor=suffix.group(1);sizes.add(suffix.group(2));}}
            } else if(candidates.isEmpty())throw new IOException("등록 품번이나 명확한 품번 표시를 읽지 못했습니다. 라벨을 다시 촬영하거나 품번을 직접 입력해 주세요.");
        }
        if(original.trim().isEmpty())warnings.add("글자를 읽지 못해 실제 바코드에 등록된 항목만 표시합니다.");
        if(!barcodeKeys.isEmpty()&&!ocrKeys.isEmpty()&&!barcodeKeys.equals(ocrKeys))warnings.add("바코드 등록 항목과 OCR 후보가 다릅니다. 두 근거의 후보를 함께 표시합니다. 다른 항목에 기존 바코드를 연결하지 마세요.");
        if(!barcodeKeys.isEmpty()&&!readSku.isEmpty())for(String key:barcodeKeys)if(!PhotoContract.sameKey(readSku,candidates.get(key).getString("sku"))){warnings.add("바코드에 등록된 품번과 라벨에서 읽힌 품번이 다릅니다. 실제 라벨과 등록 내용을 확인해 주세요.");break;}
        String explicitSize=uniqueField(EXPLICIT_SIZE,ocr.text).replace(" ","");if(!explicitSize.isEmpty()){sizes.clear();sizes.add(explicitSize);}
        if(sizes.isEmpty())for(String word:ocr.text.replaceAll("\\([^\\n)]*-.*?\\)","").split("[^A-Z0-9]+"))if(isSize(word)&&word.matches("(?:[0-9]{1,3})?(?:XXXL|XXL|XL|XS|S|M|L)"))sizes.add(word);
        if(sizes.size()>1)warnings.add("호칭이 여러 개 읽혔습니다. 호칭은 참고용이며 품번·색상이나 재고 수량을 바꾸지 않습니다.");
        if(!newBarcode.isEmpty())warnings.add("읽힌 바코드는 현재 미등록입니다. 후보를 선택해도 자동 등록되지 않으며 연결 내용을 따로 확인해야 합니다.");
        if(candidates.size()>MAX_MATCHES)throw tooMany();
        PhotoContract.Compatibility current=client.compatibility();
        if(!token.generation.equals(current.generation)||token.revision!=current.revision)throw stale();
        Bundle result=new Bundle();result.putString("text",original);result.putString("sku",readSku);
        result.putString("color",readColors.size()==1?readColors.iterator().next():suggestedColor);
        result.putString("size",sizes.size()==1?sizes.iterator().next():"");result.putString("warning",join(warnings));
        result.putParcelableArrayList("matches",new ArrayList<Bundle>(candidates.values()));result.putString("newBarcode",newBarcode);return result;
    }

    private static LinkedHashMap<String,Bundle> readCatalogue(PhotoClient client,PhotoContract.Compatibility token)throws IOException {
        if(token.generation==null||token.generation.isEmpty()||token.revision<0)throw stale();
        LinkedHashMap<String,Bundle> rows=new LinkedHashMap<String,Bundle>();int offset=0,count=0;
        do {
            Bundle page=client.labelCatalogPage("",offset,token.generation,token.revision);
            if(page==null||!PhotoContract.OK.equals(page.getString("status")))throw backend("등록 목록 조회",page);
            requireToken(page,token);ArrayList<Bundle> items=page.getParcelableArrayList("items");
            if(items==null||!page.containsKey("nextOffset"))throw new IOException("등록 목록 응답이 완전하지 않습니다. 다시 시도해 주세요.");
            for(Bundle row:items){if(++count>MAX_CATALOG)throw new IOException("등록 목록이 20,000개를 넘어 사진 구별을 마치지 못했습니다. 품번 목록에서 직접 검색해 주세요.");String id=identity(row);Bundle previous=rows.get(id);if(previous!=null&&(!sameField(previous,row,"productId")||!sameField(previous,row,"variantId")))throw stale();rows.put(id,new Bundle(row));}
            int next=page.getInt("nextOffset",-1);if(next<0)break;
            if(items.isEmpty()||next<=offset||next>MAX_CATALOG)throw new IOException("등록 목록의 다음 페이지를 안전하게 읽지 못했습니다. 품번 목록에서 검색하거나 다시 시도해 주세요.");
            offset=next;
        }while(true);return rows;
    }
    private static List<SkuHit> findSkus(Ocr text,Map<String,List<Bundle>> bySku) {
        List<SkuHit> found=new ArrayList<SkuHit>();
        Set<String> explicitSkus=new LinkedHashSet<String>();Matcher explicit=EXPLICIT_SKU.matcher(text.text);
        while(explicit.find())explicitSkus.add(PhotoContract.key(explicit.group(1)));
        for(Map.Entry<String,List<Bundle>> entry:bySku.entrySet()) {
            if(!explicitSkus.isEmpty()&&!explicitSkus.contains(entry.getKey()))continue;
            String key=flat(entry.getKey());if(key.isEmpty())continue;int at=-1;
            while((at=text.flat.indexOf(key,at+1))>=0) {
                int start=text.positions.get(at),end=text.positions.get(at+key.length()-1)+1;
                if(IntakeText.isGarmentSpan(text.text,start,end))continue;
                if(!leftBoundary(text.text,start)&&!afterMarker(text.text,start,"品番","货号","貨號","款号","款號","STYLE","ITEM","CODE"))continue;
                boolean valid=rightBoundary(text.text,end);String tail=tail(text.text,end);
                if(!valid)for(Bundle row:entry.getValue())for(String alias:aliases(row)){String color=flat(alias);if(!color.isEmpty()&&tail.startsWith(color)&&(tail.length()==color.length()||isSize(tail.substring(color.length()))))valid=true;}
                if(!valid&&tail.matches("[A-Z]{1,4}[0-9]{1,3}(?:XXXL|XXL|XL|XS|S|M|L)"))valid=true;
                if(valid)found.add(new SkuHit(entry.getKey(),start,end));
            }
        }
        List<SkuHit> result=new ArrayList<SkuHit>();
        for(SkuHit hit:found){boolean shorter=false;for(SkuHit other:found)if(other.start<=hit.start&&other.end>=hit.end&&other.end-other.start>hit.end-hit.start){shorter=true;break;}if(!shorter)result.add(hit);}
        Collections.sort(result,new Comparator<SkuHit>(){public int compare(SkuHit a,SkuHit b){return Integer.compare(a.start,b.start);}});return result;
    }
    private static boolean inSkuScope(int position,List<SkuHit> own,List<SkuHit> all,boolean single) {
        if(single)return true;
        for(SkuHit hit:own){int end=Integer.MAX_VALUE;for(SkuHit next:all)if(next.start>hit.start){end=next.start;break;}if(position>=hit.end&&position<end)return true;}return false;
    }
    private static LinkedHashSet<String> suggestSkus(Ocr ocr) {
        LinkedHashSet<String> result=new LinkedHashSet<String>();Matcher explicit=EXPLICIT_SKU.matcher(ocr.text);
        while(explicit.find()){String value=explicit.group(1);if(value.matches(".*[A-Z].*")&&value.matches(".*[0-9].*"))result.add(value);}
        if(!result.isEmpty())return result;
        Matcher style=STYLE.matcher(ocr.text);while(style.find()){if(leftBoundary(ocr.text,style.start())&&rightBoundary(ocr.text,style.end()))result.add(style.group());}return result;
    }
    private static List<String> aliases(Bundle row) {LinkedHashSet<String> aliases=new LinkedHashSet<String>();aliases.add(row.getString("sourceColor",""));String values=row.getString("colorAliases","");for(String value:values.split("\\n"))if(!value.trim().isEmpty())aliases.add(value);return new ArrayList<String>(aliases);}
    private static void addCandidate(LinkedHashMap<String,Bundle> values,Bundle row,String evidence,String safeBarcode)throws IOException {String key=identity(row);Bundle candidate=values.get(key);if(candidate==null){candidate=new Bundle(row);candidate.putString("evidence",evidence);candidate.putString("safeBarcode",safeBarcode);values.put(key,candidate);}else{String existing=candidate.getString("evidence","");if(!existing.contains(evidence))candidate.putString("evidence",existing+" · "+evidence);if(candidate.getString("safeBarcode","").isEmpty()&&!safeBarcode.isEmpty())candidate.putString("safeBarcode",safeBarcode);}if(values.size()>MAX_MATCHES)throw tooMany();}
    private static String identity(Bundle row)throws IOException {if(row==null)throw stale();String sku=PhotoContract.key(row.getString("sku")),color=PhotoContract.key(row.getString("sourceColor"));if(sku.isEmpty()||color.isEmpty())throw new IOException("등록 목록에 품번·색상이 빠져 있습니다. 유란노트에서 확인해 주세요.");return sku.length()+":"+sku+color;}
    private static boolean sameField(Bundle a,Bundle b,String name){return a.getString(name,"").equals(b.getString(name,""));}
    private static void requireToken(Bundle reply,PhotoContract.Compatibility token)throws IOException {if(reply==null||!reply.containsKey("generation")||!reply.containsKey("revision")||!token.generation.equals(reply.getString("generation"))||token.revision!=reply.getLong("revision",-1))throw stale();}
    private static IOException backend(String action,Bundle reply){return new IOException(action+"를 완료하지 못했습니다. 기존 등록 여부는 판단하지 않았습니다. 다시 시도해 주세요."+(reply==null?"":" ("+reply.getString("status","")+" / "+reply.getString("reason","")+")"));}
    private static IOException stale(){return new IOException("확인 도중 유란노트 자료가 바뀌었거나 동일한 목록인지 확인할 수 없습니다. 사진 구별을 다시 실행해 주세요.");}
    private static IOException tooMany(){return new IOException("후보가 40개를 넘어 한 항목을 구별하기 어렵습니다. 품번과 색상이 보이도록 라벨 한 장만 촬영하거나 목록에서 직접 선택해 주세요.");}
    private static String normalize(String value){return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);}
    private static String flat(String value){String text=normalize(value);StringBuilder out=new StringBuilder();for(int i=0;i<text.length();i++)if(Character.isLetterOrDigit(text.charAt(i)))out.append(text.charAt(i));return out.toString();}
    private static boolean leftBoundary(String text,int at){return at==0||!Character.isLetterOrDigit(text.charAt(at-1));}
    private static boolean rightBoundary(String text,int at){return at==text.length()||!Character.isLetterOrDigit(text.charAt(at));}
    private static String tail(String text,int at){int end=at;while(end<text.length()&&Character.isLetterOrDigit(text.charAt(end)))end++;return text.substring(at,end);}
    private static boolean isSize(String value){return value!=null&&!value.isEmpty()&&SIZE.matcher(value).matches();}
    private static boolean afterMarker(String text,int at,String...markers){String before=text.substring(0,at);for(String marker:markers)if(before.endsWith(marker)){int start=before.length()-marker.length();if(leftBoundary(text,start))return true;}return false;}
    private static String uniqueField(Pattern pattern,String text){LinkedHashSet<String> values=new LinkedHashSet<String>();Matcher match=pattern.matcher(text);while(match.find())values.add(match.group(1));return values.size()==1?values.iterator().next():"";}
    private static String join(Set<String> values){StringBuilder out=new StringBuilder();for(String value:values){if(out.length()>0)out.append('\n');out.append(value);}return out.toString();}
    private static final class Ocr {final String text,flat;final ArrayList<Integer> positions=new ArrayList<Integer>();Ocr(String value){text=normalize(value);StringBuilder f=new StringBuilder();for(int i=0;i<text.length();i++)if(Character.isLetterOrDigit(text.charAt(i))){f.append(text.charAt(i));positions.add(i);}flat=f.toString();}}
    private static final class SkuHit {final String sku;final int start,end;SkuHit(String sku,int start,int end){this.sku=sku;this.start=start;this.end=end;}}
    private static final class AliasHit {final Bundle row;final int start,end,length;final String size;AliasHit(Bundle row,int start,int end,int length,String size){this.row=row;this.start=start;this.end=end;this.length=length;this.size=size;}}
}
