package com.gongyo.photo;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.Locale;
/** Android-independent photo protocol shared by the owner and photo client. */
public final class PhotoContract {
 private PhotoContract() {}
 public static final int PROTOCOL_VERSION=1;
 /** Product and color identity is case insensitive; receipt fingerprints stay exact. */
 public static String key(String value){return value==null?"":value.trim().toUpperCase(Locale.ROOT);}
 public static boolean sameKey(String left,String right){return left!=null&&right!=null&&key(left).equals(key(right));}
 public static final String AUTHORITY="com.gongyo.orderly.photos", READ_PERMISSION="com.gongyo.orderly.permission.READ_PHOTOS", WRITE_PERMISSION="com.gongyo.orderly.permission.WRITE_PHOTOS";
 public static final String PREPARED_SOURCE_PATH_PREFIX="/prepared/", PREPARED_SOURCE_SUFFIX=".input", CAMERA_OUTPUT_PATH_PREFIX="/capture/", CAMERA_OUTPUT_SUFFIX=".jpg";
 public static String preparedSourceNonce(String uri,String expectedCaptureAuthority){return nonce(uri,expectedCaptureAuthority,PREPARED_SOURCE_PATH_PREFIX,PREPARED_SOURCE_SUFFIX);}
 public static String cameraOutputNonce(String uri,String expectedCaptureAuthority){return nonce(uri,expectedCaptureAuthority,CAMERA_OUTPUT_PATH_PREFIX,CAMERA_OUTPUT_SUFFIX);}
 private static String nonce(String uri,String authority,String route,String suffix){
  if(uri==null||authority==null||authority.isEmpty())return null;String prefix="content://"+authority+route;
  if(!uri.startsWith(prefix)||!uri.endsWith(suffix)||uri.length()!=prefix.length()+36+suffix.length())return null;
  String nonce=uri.substring(prefix.length(),prefix.length()+36);return nonce.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")?nonce:null;
 }
 public static final String OK="OK", INCOMPATIBLE="INCOMPATIBLE", CONFLICT="CONFLICT", INVALID_REQUEST="INVALID_REQUEST", LIMIT_REACHED="LIMIT_REACHED", IO_ERROR="IO_ERROR", NOT_FOUND="NOT_FOUND", STALE_PAGE="STALE_PAGE";
 public static final class RegisterRequest {
  public final String requestId,generation,productId,variantId,sku,sourceColor,sourceSha256,sourceUri;public final long revision;
  public RegisterRequest(String requestId,String generation,long revision,String productId,String variantId,String sku,String sourceColor,String sourceSha256,String sourceUri){this.requestId=requestId;this.generation=generation;this.revision=revision;this.productId=productId;this.variantId=variantId;this.sku=sku;this.sourceColor=sourceColor;this.sourceSha256=sourceSha256;this.sourceUri=sourceUri;}
 }
 public static final class Compatibility {
  public final int protocolVersion,noteVersionCode;public final String generation;public final long revision;
  public Compatibility(int protocolVersion,String generation,long revision,int noteVersionCode){this.protocolVersion=protocolVersion;this.generation=generation;this.revision=revision;this.noteVersionCode=noteVersionCode;}
 }
 public static final class Reply {
  public final String status,requestId,generation,productId,variantId,photoVersionId,reason;public final long revision;
  public Reply(String status,String requestId,String generation,long revision,String productId,String variantId,String photoVersionId,String reason){this.status=status;this.requestId=requestId;this.generation=generation;this.revision=revision;this.productId=productId;this.variantId=variantId;this.photoVersionId=photoVersionId;this.reason=reason;}
 }
 /** Length-framed UTF-8, preserving exact case/Unicode; the transport URI is excluded. */
 public static String fingerprint(RegisterRequest request)throws IOException {
  if(request==null)throw new IOException("Missing request");
  MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
  DataOutputStream out=new DataOutputStream(new DigestOutputStream(new OutputStream(){public void write(int b){}public void write(byte[] b,int off,int len){}},digest));
  out.writeInt(PROTOCOL_VERSION);string(out,request.requestId);string(out,request.generation);out.writeLong(request.revision);string(out,request.productId);string(out,request.variantId);string(out,request.sku);string(out,request.sourceColor);string(out,request.sourceSha256);out.flush();
  char[] alphabet="0123456789abcdef".toCharArray();byte[] bytes=digest.digest();char[] hash=new char[64];for(int i=0;i<bytes.length;i++){hash[i*2]=alphabet[(bytes[i]&255)>>>4];hash[i*2+1]=alphabet[bytes[i]&15];}return new String(hash);
 }
 private static void string(DataOutputStream out,String value)throws IOException {
  if(value==null)throw new IOException("Missing request field");ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);out.writeInt(bytes.length);out.write(bytes);
 }
}
