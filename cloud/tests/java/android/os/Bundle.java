package android.os;
import java.util.*;
/** JVM boundary for the Android Bundle operations used in LabelMatcher. */
public final class Bundle {
 private final Map<String,Object> values=new HashMap<>();
 public Bundle(){}
 public Bundle(Bundle b){values.putAll(b.values);}
 public void putString(String k,String v){values.put(k,v);}
 public String getString(String k){return getString(k,null);}
 public String getString(String k,String fallback){Object v=values.get(k);return v instanceof String?(String)v:fallback;}
 public void putLong(String k,long v){values.put(k,v);}
 public long getLong(String k){return getLong(k,0);}
 public long getLong(String k,long fallback){Object v=values.get(k);return v instanceof Long?(Long)v:fallback;}
 public void putInt(String k,int v){values.put(k,v);}
 public int getInt(String k,int fallback){Object v=values.get(k);return v instanceof Integer?(Integer)v:fallback;}
 public boolean containsKey(String k){return values.containsKey(k);}
 public <T> void putParcelableArrayList(String k,ArrayList<T> v){values.put(k,v);}
 @SuppressWarnings("unchecked") public <T> ArrayList<T> getParcelableArrayList(String k){return (ArrayList<T>)values.get(k);}
}
