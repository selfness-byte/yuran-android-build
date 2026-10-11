package com.gongyo.orderly;

import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.*;
import java.util.zip.*;

/** Synthetic JPEG only: frozen identity and actual XLSX image relationships. */
public final class ExportPhotoChecks {
 private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
 private static String id(){return UUID.randomUUID().toString();}
 private static Map<String,byte[]> unzip(byte[] bytes)throws IOException{
  Map<String,byte[]> entries=new HashMap<>();
  try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
   for(ZipEntry e;(e=zip.getNextEntry())!=null;){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];for(int n;(n=zip.read(b))!=-1;)out.write(b,0,n);entries.put(e.getName(),out.toByteArray());}
  }return entries;
 }
 private static String text(Map<String,byte[]> entries,String path)throws Exception{return new String(entries.get(path),"UTF-8");}
 public static void main(String[] ignored)throws Exception{
  BufferedImage image=new BufferedImage(40,24,BufferedImage.TYPE_INT_RGB);
  for(int y=0;y<24;y++)for(int x=0;x<40;x++)image.setRGB(x,y,(x<20?0x6699CC:0xCC9966));
  ByteArrayOutputStream jpeg=new ByteArrayOutputStream();check(ImageIO.write(image,"jpg",jpeg),"synthetic JPEG created");
  final byte[] bytes=jpeg.toByteArray();String hash=MediaStore.hex(MediaStore.digest().digest(bytes));
  String product=id(),variant=id();
  ExportSnapshot frozen=new ExportSnapshot();frozen.generation=id();frozen.revision=1;frozen.format="xlsx";frozen.jobCopy.title="Synthetic export";
  frozen.assets.put(hash,new PhotoCatalog.Asset(hash,bytes.length,40,24));
  for(int i=0;i<3;i++){
   Inventory.Row row=new Inventory.Row();row.sku="QA-EX"+i;row.sourceColor=row.color="TEST BLUE";row.quantities[0]=i==1?0:2;
   row.binding=new PhotoCatalog.Binding(product,variant,hash,row.sku,row.sourceColor,1,false);
   frozen.jobCopy.rows.add(row);frozen.photoVersions.add(hash);frozen.bindingRevisions.put(row.rowId,1L);
  }
  final ExportSnapshot incoming=frozen;final boolean[] touched={false};
  MediaSource source=new MediaSource(){
   public InputStream open(String requested){check(hash.equals(requested),"only frozen asset requested");return new ByteArrayInputStream(bytes);}
   public void checkReady(){if(!touched[0]){touched[0]=true;incoming.jobCopy.rows.get(0).sku="MUTATED";incoming.jobCopy.rows.get(0).quantities[0]=99;}}
  };
  ByteArrayOutputStream output=new ByteArrayOutputStream();XlsxWriter.write(output,frozen,source);Map<String,byte[]> zip=unzip(output.toByteArray());
  check(Arrays.equals(bytes,zip.get("xl/media/image1.jpg")),"exact embedded JPEG");
  check(!zip.containsKey("xl/media/image2.jpg"),"shared asset not duplicated");
  String sheet=text(zip,"xl/worksheets/sheet1.xml"),drawing=text(zip,"xl/drawings/drawing1.xml");
  check(sheet.contains("QA-EX0")&&!sheet.contains("MUTATED")&&!sheet.contains("QA-EX1")&&sheet.contains("QA-EX2"),"frozen identity/order and zero-row filtering");
  check(sheet.contains("<c r=\"D3\" s=\"3\" t=\"n\"><v>2</v>"),"frozen quantity survives source callback mutation");
  check(drawing.split("<xdr:oneCellAnchor>",-1).length-1==2,"both nonzero rows have anchors");
  check(drawing.contains("<xdr:row>2</xdr:row>")&&drawing.contains("<xdr:row>3</xdr:row>"),"anchors match compacted rows");
  check(text(zip,"xl/worksheets/_rels/sheet1.xml.rels").contains("../drawings/drawing1.xml"),"worksheet drawing relationship");
  check(text(zip,"xl/drawings/_rels/drawing1.xml.rels").contains("../media/image1.jpg"),"drawing image relationship");
  check(text(zip,"[Content_Types].xml").contains("image/jpeg"),"JPEG content type");
  final byte[] damaged=bytes.clone();damaged[damaged.length-1]^=1;
  boolean rejected=false;try{XlsxWriter.write(new ByteArrayOutputStream(),frozen,h->new ByteArrayInputStream(damaged));}catch(IOException expected){rejected=true;}
  check(rejected,"corrupt photo cannot produce successful XLSX");
  System.out.println("PASS synthetic XLSX photos, frozen rows, compacted anchors, and corrupt-byte rejection");
 }
}
