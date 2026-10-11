package com.gongyo.photo;
import java.io.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
/** A separate stock operation; retries do not become extra inventory. */
public final class IntakeContract {
 private IntakeContract(){}
 public static long[] counts(String input)throws IOException{
  if(input==null||!input.matches("[0-9]{1,6},[0-9]{1,6},[0-9]{1,6},[0-9]{1,6}"))throw new IOException("S·M·L·XL 수량을 확인해 주세요.");
  String[] parts=input.split(",");long[] q=new long[4];for(int i=0;i<4;i++)q[i]=Long.parseLong(parts[i]);return q;
 }
 public static String counts(long[] values)throws IOException{validateCounts(values);return values[0]+","+values[1]+","+values[2]+","+values[3];}
 public static void validateCounts(long[] q)throws IOException{
  if(q==null||q.length!=4)throw new IOException("사이즈별 수량이 없습니다.");long total=0;
  for(long n:q){if(n<0||n>999999)throw new IOException("수량은 0~999999개여야 합니다.");total+=n;}
  if(total==0)throw new IOException("입고 수량을 입력해 주세요.");
 }
 public static String fingerprint(String generation,String hash,String sku,String color,long[] counts)throws IOException{
  if(generation==null||!generation.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||hash==null||!hash.matches("[0-9a-f]{64}"))throw new IOException("입고 사진 식별자가 올바르지 않습니다.");
  sku=PhotoContract.key(sku);color=PhotoContract.key(color);
  if(sku.isEmpty()||sku.length()>80||color.isEmpty()||color.length()>30)throw new IOException("품번과 색상을 확인해 주세요.");
  for(char c:(sku+color).toCharArray())if(Character.isISOControl(c))throw new IOException("품번 또는 색상에 제어 문자가 있습니다.");
  validateCounts(counts);
  try{
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
   out.writeUTF("YURAN-INTAKE-1");out.writeUTF(generation);out.writeUTF(hash);out.writeUTF(sku);out.writeUTF(color);for(long q:counts)out.writeLong(q);out.close();
   byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();
  }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
 }
}
