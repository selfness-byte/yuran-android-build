package com.gongyo.orderly;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class XlsxWriter {
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final long PHOTO_EMU = 792000; // 22mm, 36000EMU per mm.
    private static final double MIN_ROW_POINTS = 9360.0 / 127; // 26mm.

    /** The legacy entry remains available for jobs with no photo source. */
    static void write(OutputStream out, Inventory.Job job) throws IOException {
        validate(job);
        writePackage(out, job.copy(), null, null);
    }

    static void write(OutputStream out, ExportSnapshot snapshot, MediaSource media) throws IOException {
        // Detach before calling media: even a callback cannot change frozen output identity/order.
        ExportSnapshot frozen = ExportSnapshot.decode(ExportSnapshot.encode(snapshot));
        frozen.verifyMedia(media);
        validate(frozen.jobCopy);
        writePackage(out, frozen.jobCopy, frozen, media);
    }

    private static void writePackage(OutputStream out, Inventory.Job job, ExportSnapshot frozen,
                                     MediaSource media) throws IOException {
        List<Integer> indices = new ArrayList<>();
        Map<String,Integer> images = new LinkedHashMap<>();
        for (int i=0; i<job.rows.size(); i++) {
            if (frozen != null && job.rows.get(i).total()==0) continue;
            indices.add(i);
            String hash = frozen==null ? "" : frozen.photoVersions.get(i);
            if (!hash.isEmpty() && !images.containsKey(hash)) images.put(hash, images.size()+1);
        }
        if (indices.isEmpty()) throw new IllegalArgumentException("출력할 품목이 없습니다.");
        boolean photos = !images.isEmpty();
        ZipOutputStream zip = new ZipOutputStream(out);
        String photoTypes = photos ? "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>" : "";
        entry(zip, "[Content_Types].xml", XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" + photoTypes + "</Types>");
        entry(zip, "_rels/.rels", XML + "<Relationships xmlns=\""+REL+"\"><Relationship Id=\"rId1\" Type=\""+R+"/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        int lastRow = indices.size() + 3;
        entry(zip, "xl/workbook.xml", XML + "<workbook xmlns=\"" + NS + "\" xmlns:r=\""+R+"\"><sheets><sheet name=\"주문표\" sheetId=\"1\" r:id=\"rId1\"/></sheets><definedNames><definedName name=\"_xlnm.Print_Area\" localSheetId=\"0\">'주문표'!$A$1:$H$" + lastRow + "</definedName><definedName name=\"_xlnm.Print_Titles\" localSheetId=\"0\">'주문표'!$1:$2</definedName></definedNames></workbook>");
        entry(zip, "xl/_rels/workbook.xml.rels", XML + "<Relationships xmlns=\""+REL+"\"><Relationship Id=\"rId1\" Type=\""+R+"/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\""+R+"/styles\" Target=\"styles.xml\"/></Relationships>");
        entry(zip, "xl/styles.xml", styles());
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
        Writer writer = new OutputStreamWriter(zip, "UTF-8");
        writer.write(XML + "<worksheet xmlns=\"" + NS + "\" xmlns:r=\""+R+"\"><sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr><dimension ref=\"A1:H" + lastRow + "\"/><sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\"><pane ySplit=\"2\" topLeftCell=\"A3\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"28\"/><cols><col min=\"1\" max=\"1\" width=\"14\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"27\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"15\" customWidth=\"1\"/><col min=\"4\" max=\"7\" width=\"11\" customWidth=\"1\"/><col min=\"8\" max=\"8\" width=\"16\" customWidth=\"1\"/></cols><sheetData>");
        writer.write("<row r=\"1\" ht=\"42\" customHeight=\"1\">" + textCell("A1", safeTitle(job), 1) + "</row><row r=\"2\" ht=\"32\" customHeight=\"1\">");
        String[] labels = {"사진", "품번", "색상", "S", "M", "L", "XL", "합계"};
        for (int c=0; c<labels.length; c++) writer.write(textCell(address(c,2),labels[c],2));
        writer.write("</row>");
        long[] totals = new long[4]; long grand = 0;
        StringBuilder drawing = new StringBuilder(XML+"<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\""+R+"\">");
        int pictureId=0;
        for (int i=0; i<indices.size(); i++) {
            int original=indices.get(i), r=i+3, style=i%2==0?3:4;
            Inventory.Row row=job.rows.get(original);
            int lines=Math.max(1,Math.max(displayLines(row.sku,20),displayLines(row.color,10)));
            double height=Math.min(409,Math.max(MIN_ROW_POINTS,lines*19+8));
            writer.write("<row r=\""+r+"\" ht=\""+String.format(java.util.Locale.ROOT,"%.3f",height)+"\" customHeight=\"1\">");
            writer.write(textCell(address(0,r),"",style));
            writer.write(textCell(address(1,r),row.sku,style));writer.write(textCell(address(2,r),row.color,style));
            long sum=0;
            for (int c=0; c<4; c++) {
                long q=row.quantities[c];totals[c]=add(totals[c],q);sum=add(sum,q);
                writer.write(numberCell(address(c+3,r),q,style));
            }
            grand=add(grand,sum);writer.write(numberCell(address(7,r),sum,style));writer.write("</row>");
            String hash=frozen==null?"":frozen.photoVersions.get(original);
            if (!hash.isEmpty()) drawing.append(anchor(r,height,frozen.assets.get(hash),images.get(hash),++pictureId));
        }
        writer.write("<row r=\""+lastRow+"\" ht=\"36\" customHeight=\"1\">"+textCell(address(1,lastRow),"전체 합계",5)+textCell(address(2,lastRow),indices.size()+"행",5));
        for (int c=0; c<4; c++) writer.write(numberCell(address(c+3,lastRow),totals[c],5));
        writer.write(numberCell(address(7,lastRow),grand,5));
        writer.write("</row></sheetData><autoFilter ref=\"A2:H"+(lastRow-1)+"\"/><mergeCells count=\"1\"><mergeCell ref=\"A1:H1\"/></mergeCells><printOptions horizontalCentered=\"1\"/><pageMargins left=\"0.25\" right=\"0.25\" top=\"0.3\" bottom=\"0.3\" header=\"0.12\" footer=\"0.12\"/><pageSetup paperSize=\"9\" orientation=\"portrait\" fitToWidth=\"1\" fitToHeight=\"0\"/><headerFooter><oddFooter>&amp;C&amp;P / &amp;N</oddFooter></headerFooter>"+(photos?"<drawing r:id=\"rId1\"/>":"")+"</worksheet>");
        writer.flush();zip.closeEntry();
        if (photos) {
            entry(zip,"xl/worksheets/_rels/sheet1.xml.rels",XML+"<Relationships xmlns=\""+REL+"\"><Relationship Id=\"rId1\" Type=\""+R+"/drawing\" Target=\"../drawings/drawing1.xml\"/></Relationships>");
            entry(zip,"xl/drawings/drawing1.xml",drawing.append("</xdr:wsDr>").toString());
            StringBuilder relationships=new StringBuilder(XML+"<Relationships xmlns=\""+REL+"\">");
            for (Map.Entry<String,Integer> image:images.entrySet()) {
                relationships.append("<Relationship Id=\"rId").append(image.getValue()).append("\" Type=\"").append(R).append("/image\" Target=\"../media/image").append(image.getValue()).append(".jpg\"/>");
                streamMedia(zip,"xl/media/image"+image.getValue()+".jpg",frozen.assets.get(image.getKey()),media);
            }
            entry(zip,"xl/drawings/_rels/drawing1.xml.rels",relationships.append("</Relationships>").toString());
        }
        if (media!=null) media.checkReady();
        zip.finish();zip.flush();
    }

    private static String anchor(int row,double height,PhotoCatalog.Asset asset,int image,int id) {
        int edge=Math.max(asset.width,asset.height);
        long w=PHOTO_EMU*asset.width/edge, h=PHOTO_EMU*asset.height/edge;
        long left=72000+(PHOTO_EMU-w)/2, top=(Math.round(height*12700)-h)/2;
        return "<xdr:oneCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>"+left+"</xdr:colOff><xdr:row>"+(row-1)+"</xdr:row><xdr:rowOff>"+top+"</xdr:rowOff></xdr:from><xdr:ext cx=\""+w+"\" cy=\""+h+"\"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\""+id+"\" name=\"사진 "+id+"\"/><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"rId"+image+"\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\""+w+"\" cy=\""+h+"\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>";
    }

    /** Verify the exact bytes actually written, not only the earlier preflight read. */
    private static void streamMedia(ZipOutputStream zip,String path,PhotoCatalog.Asset asset,
                                    MediaSource source) throws IOException {
        source.checkReady();zip.putNextEntry(new ZipEntry(path));
        MessageDigest digest=MediaStore.digest();long count=0;
        try (InputStream in=source.open(asset.hash)) {
            if (in==null) throw new IOException("Missing export photo");
            byte[] block=new byte[32768];
            for (int n; (n=in.read(block))!=-1;) {
                if (n==0) {int value=in.read();if(value==-1)break;block[0]=(byte)value;n=1;}
                if (n>asset.byteLength-count) throw new IOException("Export photo length");
                zip.write(block,0,n);digest.update(block,0,n);count+=n;
            }
        }
        if (count!=asset.byteLength || !MediaStore.hex(digest.digest()).equals(asset.hash))
            throw new IOException("Export photo bytes/hash");
        zip.closeEntry();
    }
    static void validate(Inventory.Job job) {
        if (job == null || job.rows == null || job.rows.isEmpty()) throw new IllegalArgumentException("출력할 품목이 없습니다.");
        long total=0;
        for(Inventory.Row row:job.rows) {
            if(row==null || row.quantities==null || row.quantities.length!=4) throw new IllegalArgumentException("품목의 사이즈 수량을 확인해 주세요.");
            for(long q:row.quantities) { if(q<0)throw new IllegalArgumentException("수량은 0 이상이어야 합니다."); total=add(total,q); }
        }
    }
    private static long add(long a,long b) {
        if (b > Long.MAX_VALUE-a) throw new IllegalArgumentException("합계가 지원 범위를 넘었습니다.");
        return a+b;
    }
    static String safeTitle(Inventory.Job job) { return job.title==null || job.title.trim().isEmpty() ? "주문표" : job.title; }
    private static String address(int c,int r) { return String.valueOf((char)('A'+c))+r; }
    private static String numberCell(String address,long value,int style) {
        // Excel stores only 15 significant decimal digits; text retains an exceptional long exactly.
        if(value>999999999999999L) return textCell(address,Long.toString(value),style);
        return "<c r=\""+address+"\" s=\""+style+"\" t=\"n\"><v>"+value+"</v></c>";
    }
    private static String textCell(String address,String value,int style) { return "<c r=\""+address+"\" s=\""+style+"\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"+xml(value)+"</t></is></c>"; }
    private static String xml(String text) {
        if(text==null) return "";
        StringBuilder out=new StringBuilder();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='&')out.append("&amp;"); else if(c=='<')out.append("&lt;"); else if(c=='>')out.append("&gt;");
            else if(c=='\"')out.append("&quot;"); else if(c=='\'')out.append("&apos;");
            else if(c<32 && c!='\n' && c!='\r' && c!='\t')out.append(String.format(java.util.Locale.ROOT,"_x%04X_",(int)c));
            else if(c=='_' && i+6<text.length() && text.substring(i).matches("(?s)^_x[0-9A-Fa-f]{4}_.*"))out.append("_x005F_");
            else if(c==0xFFFE || c==0xFFFF)out.append('\uFFFD');
            else if(Character.isHighSurrogate(c)) {
                if(i+1<text.length() && Character.isLowSurrogate(text.charAt(i+1)))out.append(c).append(text.charAt(++i)); else out.append('\uFFFD');
            } else if(Character.isLowSurrogate(c))out.append('\uFFFD'); else out.append(c);
        }
        return out.toString();
    }
    private static int displayLines(String text,int width) {
        if(text==null || text.isEmpty())return 1;
        int lines=0; for(String part:text.split("\\n",-1)) {
            int units=0; for(int i=0;i<part.length();) { int cp=part.codePointAt(i);units+=cp>255?2:1;i+=Character.charCount(cp); }
            lines+=Math.max(1,(units+width-1)/width);
        }
        return lines;
    }
    private static void entry(ZipOutputStream zip,String name,String contents) throws IOException { zip.putNextEntry(new ZipEntry(name));zip.write(contents.getBytes("UTF-8"));zip.closeEntry(); }
    private static String styles() {
        StringBuilder s=new StringBuilder(XML+"<styleSheet xmlns=\""+NS+"\"><fonts count=\"4\"><font><sz val=\"14\"/><name val=\"Arial\"/></font><font><b/><sz val=\"22\"/><name val=\"Arial\"/></font><font><b/><sz val=\"15\"/><color rgb=\"FFFFFFFF\"/><name val=\"Arial\"/></font><font><b/><sz val=\"16\"/><name val=\"Arial\"/></font></fonts><fills count=\"5\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF253347\"/><bgColor indexed=\"64\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE9EDF2\"/><bgColor indexed=\"64\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD4DDE8\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"3\"><border><left/><right/><top/><bottom/><diagonal/></border>");
        for(String weight:new String[]{"thin","medium"}) { s.append("<border>");for(String edge:new String[]{"left","right","top","bottom"})s.append("<").append(edge).append(" style=\"").append(weight).append("\"><color rgb=\"FF334155\"/></").append(edge).append(">");s.append("<diagonal/></border>"); }
        s.append("</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"6\">");
        s.append(style(0,0,0,"center"));s.append(style(1,0,0,"left"));s.append(style(2,2,2,"center"));s.append(style(0,0,1,"center"));s.append(style(0,3,1,"center"));s.append(style(3,4,2,"center"));
        return s.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>").toString();
    }
    private static String style(int font,int fill,int border,String align) { return "<xf numFmtId=\"1\" fontId=\""+font+"\" fillId=\""+fill+"\" borderId=\""+border+"\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\""+align+"\" vertical=\"center\" wrapText=\"1\"/></xf>"; }
}
