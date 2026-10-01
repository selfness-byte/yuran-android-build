package com.gongyo.orderly;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
/** Strict bounded JSON for the single manifest v1 vocabulary, without product dependencies. */
public final class BackupManifestCodec {
 private BackupManifestCodec(){}
 public static byte[] encode(BackupManifest m)throws IOException {
  m.validate();StringBuilder s=new StringBuilder("{\"formatVersion\":1,\"stateVersion\":").append(m.stateVersion).append(",\"stateSha256\":\"").append(m.stateSha256).append("\",\"stateBytes\":").append(m.stateBytes).append(",\"media\":[");boolean first=true;
  for(PhotoCatalog.Asset a:m.media.values()){if(!first)s.append(',');first=false;s.append("{\"sha256\":\"").append(a.hash).append("\",\"byteLength\":").append(a.byteLength).append(",\"width\":").append(a.width).append(",\"height\":").append(a.height).append('}');}byte[] b=s.append("]}").toString().getBytes(StandardCharsets.UTF_8);if(b.length>8388608)throw new IOException("Manifest limit");return b;
 }
 public static BackupManifest decode(byte[] b)throws IOException {
  if(b==null||b.length>8388608)throw new IOException("Manifest limit");String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}catch(CharacterCodingException bad){throw new IOException("Manifest UTF-8",bad);}
  Parser p=new Parser(text);Map<String,Object> o=p.object(false);p.ws();if(p.at!=text.length())throw new IOException("Manifest trailing input");keys(o,"formatVersion","stateVersion","stateSha256","stateBytes","media");BackupManifest m=new BackupManifest();m.formatVersion=integer(o,"formatVersion");m.stateVersion=integer(o,"stateVersion");m.stateSha256=string(o,"stateSha256");m.stateBytes=number(o,"stateBytes");Object entries=o.get("media");if(!(entries instanceof List))throw new IOException("Manifest media array");
  for(Object entry:(List<?>)entries){if(!(entry instanceof Map))throw new IOException("Manifest asset object");@SuppressWarnings("unchecked") Map<String,Object> a=(Map<String,Object>)entry;keys(a,"sha256","byteLength","width","height");PhotoCatalog.Asset asset=new PhotoCatalog.Asset(string(a,"sha256"),number(a,"byteLength"),integer(a,"width"),integer(a,"height"));if(m.media.put(asset.hash,asset)!=null)throw new IOException("Duplicate manifest asset");}m.validate();return m;
 }
 private static void keys(Map<String,Object> o,String... keys)throws IOException{if(!o.keySet().equals(new HashSet<>(Arrays.asList(keys))))throw new IOException("Unknown/missing manifest key");}
 private static String string(Map<String,Object> o,String k)throws IOException{Object v=o.get(k);if(!(v instanceof String))throw new IOException("Manifest string "+k);return (String)v;}
 private static long number(Map<String,Object> o,String k)throws IOException{Object v=o.get(k);if(!(v instanceof Long))throw new IOException("Manifest integer "+k);return (Long)v;}
 private static int integer(Map<String,Object> o,String k)throws IOException{long n=number(o,k);if(n>Integer.MAX_VALUE)throw new IOException("Manifest integer overflow");return (int)n;}
 private static final class Parser {
  final String text;int at;Parser(String t){text=t;}
  void ws(){while(at<text.length()&&" \r\n\t".indexOf(text.charAt(at))>=0)at++;}
  boolean take(char c){ws();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
  void need(char c)throws IOException{if(!take(c))throw new IOException("Manifest expected "+c);}
  Map<String,Object> object(boolean asset)throws IOException {need('{');Map<String,Object> o=new LinkedHashMap<>();if(take('}'))return o;do{String k=str();need(':');Object v;if(!asset&&k.equals("media")){need('[');List<Object> list=new ArrayList<>();if(!take(']')){do{if(list.size()>=PhotoLimits.MAX_PHOTO_ASSETS)throw new IOException("Manifest media limit");list.add(object(true));}while(take(','));need(']');}v=list;}else{ws();if(at<text.length()&&text.charAt(at)=='"')v=str();else v=num();}if(o.put(k,v)!=null)throw new IOException("Duplicate JSON key");if(o.size()>5)throw new IOException("Manifest key count");}while(take(','));need('}');return o;}
  String str()throws IOException {need('"');StringBuilder b=new StringBuilder();while(at<text.length()){char c=text.charAt(at++);if(c=='"')return b.toString();if(c<32)throw new IOException("JSON control");if(c=='\\'){if(at>=text.length())throw new IOException("JSON escape");char e=text.charAt(at++);switch(e){case '"':case '\\':case '/':c=e;break;case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'u':if(at+4>text.length())throw new IOException("JSON unicode");try{c=(char)Integer.parseInt(text.substring(at,at+4),16);}catch(NumberFormatException bad){throw new IOException("JSON unicode",bad);}at+=4;break;default:throw new IOException("JSON escape");}}b.append(c);if(b.length()>80)throw new IOException("Manifest string limit");}throw new IOException("JSON string truncated");}
  Long num()throws IOException {ws();int start=at;while(at<text.length()&&text.charAt(at)>='0'&&text.charAt(at)<='9')at++;if(start==at||at-start>1&&text.charAt(start)=='0')throw new IOException("JSON integer");try{return Long.valueOf(text.substring(start,at));}catch(NumberFormatException bad){throw new IOException("JSON overflow",bad);}}
 }
}
