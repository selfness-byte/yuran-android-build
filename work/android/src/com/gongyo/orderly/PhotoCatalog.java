package com.gongyo.orderly;

import java.io.IOException;
import java.util.*;

/** Metadata values only. Registered JPEG bytes live outside this catalogue. */
public final class PhotoCatalog {
    public final LinkedHashMap<String,Product> products=new LinkedHashMap<>();
    public final LinkedHashMap<String,Variant> variants=new LinkedHashMap<>();
    public final LinkedHashMap<String,Asset> assets=new LinkedHashMap<>();
    public final LinkedHashMap<String,Receipt> receipts=new LinkedHashMap<>();

    public static final class Product {
        public String id,sku;
        public List<String> confirmedSkuAliases;
        public Product(String id,String sku,List<String> aliases){this.id=id;this.sku=sku;this.confirmedSkuAliases=new ArrayList<>(aliases);}
        public Product copy(){return new Product(id,sku,confirmedSkuAliases);}
    }
    public static final class Variant {
        public String id,productId,sourceColor,currentPhotoVersionId;
        public List<String> confirmedColorAliases;
        public List<String> barcodes=new ArrayList<>();
        public Variant(String id,String productId,String sourceColor,List<String> aliases,String photoVersionId){this.id=id;this.productId=productId;this.sourceColor=sourceColor;this.confirmedColorAliases=new ArrayList<>(aliases);this.currentPhotoVersionId=photoVersionId;}
        public Variant copy(){Variant v=new Variant(id,productId,sourceColor,confirmedColorAliases,currentPhotoVersionId);v.barcodes=new ArrayList<>(barcodes);return v;}
    }
    public static final class Asset {
        public String hash;
        public long byteLength;
        public int width,height;
        public Asset(String hash,long byteLength,int width,int height){this.hash=hash;this.byteLength=byteLength;this.width=width;this.height=height;}
        public Asset copy(){return new Asset(hash,byteLength,width,height);}
    }
    public static final class Binding {
        public String productId,variantId,photoVersionId,skuAtBinding,sourceColorAtBinding;
        public long bindingRevision;
        public boolean needsConfirmation;
        public Binding(String productId,String variantId,String photoVersionId,String sku,String color,long revision,boolean needsConfirmation){this.productId=productId;this.variantId=variantId;this.photoVersionId=photoVersionId;this.skuAtBinding=sku;this.sourceColorAtBinding=color;this.bindingRevision=revision;this.needsConfirmation=needsConfirmation;}
        public Binding copy(){return new Binding(productId,variantId,photoVersionId,skuAtBinding,sourceColorAtBinding,bindingRevision,needsConfirmation);}
        @Override public boolean equals(Object other){if(!(other instanceof Binding))return false;Binding b=(Binding)other;return Objects.equals(productId,b.productId)&&Objects.equals(variantId,b.variantId)&&Objects.equals(photoVersionId,b.photoVersionId)&&Objects.equals(skuAtBinding,b.skuAtBinding)&&Objects.equals(sourceColorAtBinding,b.sourceColorAtBinding)&&bindingRevision==b.bindingRevision&&needsConfirmation==b.needsConfirmation;}
        @Override public int hashCode(){return Objects.hash(productId,variantId,photoVersionId,skuAtBinding,sourceColorAtBinding,bindingRevision,needsConfirmation);}
    }
    public static final class Receipt {
        public String requestId,requestContentFingerprint,photoVersionId;
        public Binding binding;
        public long committedRevision;
        public Receipt(String requestId,String fingerprint,Binding binding,String photoVersionId,long revision){this.requestId=requestId;this.requestContentFingerprint=fingerprint;this.binding=binding;this.photoVersionId=photoVersionId;this.committedRevision=revision;}
        public Receipt copy(){return new Receipt(requestId,requestContentFingerprint,binding==null?null:binding.copy(),photoVersionId,committedRevision);}
    }
    public static final class Pin {
        public static final String REGISTRATION="REGISTRATION",EXPORT="EXPORT",BACKUP="BACKUP",RECOVERY="RECOVERY";
        public String token,kind,generation,snapshotFile;
        public List<String> photoVersionIds;
        public Pin(String token,String kind,String generation,List<String> hashes,String snapshotFile){this.token=token;this.kind=kind;this.generation=generation;this.photoVersionIds=new ArrayList<>(hashes);this.snapshotFile=snapshotFile;}
        public Pin copy(){return new Pin(token,kind,generation,photoVersionIds,snapshotFile);}
        public void validate()throws IOException {
            uuid(token);uuid(generation);
            require(REGISTRATION.equals(kind)||EXPORT.equals(kind)||BACKUP.equals(kind)||RECOVERY.equals(kind),"pin kind");
            size(photoVersionIds,PhotoLimits.MAX_PHOTO_ASSETS);Set<String> unique=new HashSet<>();
            for(String h:photoVersionIds){hash(h);require(unique.add(h),"duplicate pin hash");}
            require(snapshotFile!=null&&snapshotFile.length()<=4096&&!snapshotFile.contains(":")&&!snapshotFile.contains("\u0000"),"pin snapshot path");
        }
    }
    public PhotoCatalog copy(){PhotoCatalog c=new PhotoCatalog();for(Map.Entry<String,Product> e:products.entrySet())c.products.put(e.getKey(),e.getValue().copy());for(Map.Entry<String,Variant> e:variants.entrySet())c.variants.put(e.getKey(),e.getValue().copy());for(Map.Entry<String,Asset> e:assets.entrySet())c.assets.put(e.getKey(),e.getValue().copy());for(Map.Entry<String,Receipt> e:receipts.entrySet())c.receipts.put(e.getKey(),e.getValue().copy());return c;}

    public void validate(long revision)throws IOException {
        require(revision>=0,"revision");size(products,PhotoLimits.MAX_PRODUCTS);size(variants,PhotoLimits.MAX_VARIANTS);size(assets,PhotoLimits.MAX_PHOTO_ASSETS);size(receipts,PhotoLimits.MAX_RECEIPTS);
        int aliases=0;Set<String> skus=new HashSet<>(),barcodes=new HashSet<>();Map<String,Set<String>> colors=new HashMap<>();
        for(Map.Entry<String,Product> e:products.entrySet()){
            Product p=e.getValue();require(p!=null,"product");uuid(p.id);require(p.id.equals(e.getKey()),"product key");text(p.sku,PhotoLimits.MAX_SKU_CHARS);require(skus.add(p.sku),"duplicate SKU");size(p.confirmedSkuAliases,PhotoLimits.MAX_CONFIRMED_ALIASES);
            for(String a:p.confirmedSkuAliases){text(a,PhotoLimits.MAX_SKU_CHARS);require(skus.add(a),"duplicate SKU alias");}aliases+=p.confirmedSkuAliases.size();require(aliases<=PhotoLimits.MAX_CONFIRMED_ALIASES,"alias limit");
        }
        for(Map.Entry<String,Asset> e:assets.entrySet()){
            Asset a=e.getValue();require(a!=null,"asset");hash(a.hash);require(a.hash.equals(e.getKey()),"asset key");require(a.byteLength>0&&a.byteLength<=PhotoLimits.MAX_INPUT_BYTES&&a.width>0&&a.height>0&&a.width<=PhotoLimits.MAX_REGISTERED_EDGE&&a.height<=PhotoLimits.MAX_REGISTERED_EDGE,"asset dimensions or bytes");
        }
        for(Map.Entry<String,Variant> e:variants.entrySet()){
            Variant v=e.getValue();require(v!=null,"variant");uuid(v.id);uuid(v.productId);require(v.id.equals(e.getKey())&&products.containsKey(v.productId),"variant identity");text(v.sourceColor,PhotoLimits.MAX_SOURCE_COLOR_CHARS);
            Set<String> names=colors.get(v.productId);if(names==null){names=new HashSet<>();colors.put(v.productId,names);}require(names.add(v.sourceColor),"duplicate color");size(v.confirmedColorAliases,PhotoLimits.MAX_CONFIRMED_ALIASES);
            for(String a:v.confirmedColorAliases){text(a,PhotoLimits.MAX_SOURCE_COLOR_CHARS);require(names.add(a),"duplicate color alias");}aliases+=v.confirmedColorAliases.size();require(aliases<=PhotoLimits.MAX_CONFIRMED_ALIASES,"alias limit");
            require(v.currentPhotoVersionId!=null,"current photo");if(!v.currentPhotoVersionId.isEmpty()){hash(v.currentPhotoVersionId);require(assets.containsKey(v.currentPhotoVersionId),"missing current asset");}
            size(v.barcodes,PhotoLimits.MAX_BARCODES_PER_VARIANT);for(String barcode:v.barcodes){barcode(barcode);require(barcodes.add(barcode),"duplicate barcode");}
        }
        for(Map.Entry<String,Receipt> e:receipts.entrySet()){
            Receipt r=e.getValue();require(r!=null,"receipt");uuid(r.requestId);require(r.requestId.equals(e.getKey()),"receipt key");hash(r.requestContentFingerprint);hash(r.photoVersionId);require(r.committedRevision>=0&&r.committedRevision<=revision,"receipt revision");
            // Historical results are not live references. GC can remove their old assets.
            if(r.binding!=null){validateBinding(r.binding,r.committedRevision,false);require(r.photoVersionId.equals(r.binding.photoVersionId),"receipt photo mismatch");}
        }
    }
    public void validateBinding(Binding b,long revision,boolean live)throws IOException {
        if(b==null)return;uuid(b.productId);uuid(b.variantId);hash(b.photoVersionId);text(b.skuAtBinding,PhotoLimits.MAX_SKU_CHARS);text(b.sourceColorAtBinding,PhotoLimits.MAX_SOURCE_COLOR_CHARS);require(b.bindingRevision>=0&&b.bindingRevision<=revision,"binding revision");
        if(live){Variant v=variants.get(b.variantId);require(products.containsKey(b.productId)&&v!=null&&v.productId.equals(b.productId)&&assets.containsKey(b.photoVersionId),"missing binding reference");}
    }
    static void uuid(String value)throws IOException{require(value!=null&&value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),"UUID36");}
    static void hash(String value)throws IOException{require(value!=null&&value.matches("[0-9a-f]{64}"),"SHA-256");}
    static void text(String value,int max)throws IOException{require(value!=null&&!value.trim().isEmpty()&&value.length()<=max,"text length");}
    static void barcode(String value)throws IOException{require(value!=null&&!value.isEmpty()&&value.equals(value.trim())&&value.length()<=PhotoLimits.MAX_BARCODE_CHARS,"barcode length");for(int i=0;i<value.length();i++)require(!Character.isISOControl(value.charAt(i)),"barcode control character");}
    static void size(Map<?,?> value,int max)throws IOException{require(value!=null&&value.size()<=max,"map limit");}
    static void size(Collection<?> value,int max)throws IOException{require(value!=null&&value.size()<=max,"list limit");}
    static void require(boolean ok,String reason)throws IOException{if(!ok)throw new IOException("사진 자료가 올바르지 않습니다: "+reason);}
}
