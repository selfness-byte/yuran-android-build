package com.gongyo.orderly;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Detached schema-10 export values; no live repository lookup during rendering. */
public final class ExportSnapshot {
    private static final int MAGIC=0x45583130;
    public Inventory.Job jobCopy=new Inventory.Job();
    public String generation="",format="",pinToken="";
    public long revision;
    public Map<String,String> options=new LinkedHashMap<>();
    public Map<String,Long> bindingRevisions=new LinkedHashMap<>();
    /** One version per row in row order; empty means a blank photo cell. */
    public List<String> photoVersions=new ArrayList<>();
    /** Includes unconfirmed binding references for protection, never for display. */
    public Map<String,PhotoCatalog.Asset> assets=new LinkedHashMap<>();
    public List<String> rowIds(){List<String> ids=new ArrayList<>();for(Inventory.Row r:jobCopy.rows)ids.add(r.rowId);return ids;}

    public static ExportSnapshot capture(InventoryRepository.Snapshot snapshot,String jobId)throws IOException {
        if(snapshot==null)throw new IOException("Missing export state");
        Inventory.Job job=snapshot.stateCopy.draft;
        if(jobId!=null){job=null;for(Inventory.Job candidate:snapshot.stateCopy.jobs)if(jobId.equals(candidate.id)){if(job!=null)throw new IOException("Ambiguous export job");job=candidate;}if(job==null)throw new IOException("Export job not found");}
        ExportSnapshot e=new ExportSnapshot();e.generation=snapshot.generation;e.revision=snapshot.revision;e.jobCopy=job.copy();
        for(Inventory.Row row:e.jobCopy.rows){
            if(jobId==null)row.binding=PhotoBindings.displayBinding(snapshot.stateCopy,row);
            PhotoCatalog.Binding b=row.binding;
            e.bindingRevisions.put(row.rowId,b==null?0:b.bindingRevision);
            e.photoVersions.add(b==null||b.needsConfirmation?"":b.photoVersionId);
            if(b!=null){PhotoCatalog.Asset a=snapshot.stateCopy.catalog.assets.get(b.photoVersionId);if(a==null)throw new IOException("Missing frozen asset");e.assets.put(a.hash,a.copy());}
        }
        validate(e);return e;
    }
    public static byte[] encode(ExportSnapshot e)throws IOException {
        validate(e);ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(new StateCodec.LimitedOutputStream(buffer))){
            out.writeInt(MAGIC);out.writeInt(1);StateCodec.writeString(out,e.generation,36);out.writeLong(e.revision);
            StateCodec.writeJob(out,e.jobCopy);StateCodec.writeString(out,e.format,12);StateCodec.writeString(out,e.pinToken,36);
            out.writeInt(e.options.size());for(Map.Entry<String,String> option:e.options.entrySet()){StateCodec.writeString(out,option.getKey(),80);StateCodec.writeString(out,option.getValue(),512);}
            out.writeInt(e.bindingRevisions.size());for(Map.Entry<String,Long> b:e.bindingRevisions.entrySet()){StateCodec.writeString(out,b.getKey(),36);out.writeLong(b.getValue());}
            out.writeInt(e.photoVersions.size());for(String hash:e.photoVersions)StateCodec.writeString(out,hash,64);
            out.writeInt(e.assets.size());for(PhotoCatalog.Asset a:e.assets.values()){StateCodec.writeString(out,a.hash,64);out.writeLong(a.byteLength);out.writeInt(a.width);out.writeInt(a.height);}
        }return buffer.toByteArray();
    }
    public static ExportSnapshot decode(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length>PhotoLimits.MAX_METADATA_BYTES)throw new IOException("Export size");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=MAGIC||in.readInt()!=1)throw new IOException("Export format");ExportSnapshot e=new ExportSnapshot();
            e.generation=StateCodec.readString(in,36);e.revision=in.readLong();e.jobCopy=StateCodec.readJob(in,10);e.format=StateCodec.readString(in,12);e.pinToken=StateCodec.readString(in,36);
            int n=StateCodec.readCount(in,20);for(int i=0;i<n;i++){String k=StateCodec.readString(in,80),v=StateCodec.readString(in,512);if(e.options.put(k,v)!=null)throw new IOException("Duplicate export option");}
            n=StateCodec.readCount(in,Inventory.MAX_ROWS);for(int i=0;i<n;i++){String id=StateCodec.readString(in,36);if(e.bindingRevisions.put(id,in.readLong())!=null)throw new IOException("Duplicate export row");}
            n=StateCodec.readCount(in,Inventory.MAX_ROWS);for(int i=0;i<n;i++)e.photoVersions.add(StateCodec.readString(in,64));
            n=StateCodec.readCount(in,PhotoLimits.MAX_PHOTO_ASSETS);for(int i=0;i<n;i++){PhotoCatalog.Asset a=new PhotoCatalog.Asset(StateCodec.readString(in,64),in.readLong(),in.readInt(),in.readInt());if(e.assets.put(a.hash,a)!=null)throw new IOException("Duplicate export asset");}
            if(in.read()!=-1)throw new IOException("Export trailing bytes");validate(e);return e;
        }
    }
    static boolean isEncoded(byte[] bytes){return bytes!=null&&bytes.length>=4&&((bytes[0]&255)<<24|(bytes[1]&255)<<16|(bytes[2]&255)<<8|(bytes[3]&255))==MAGIC;}
    static void validate(ExportSnapshot e)throws IOException {
        if(e==null||e.jobCopy==null)throw new IOException("Missing export");PhotoCatalog.uuid(e.generation);PhotoCatalog.require(e.revision>=0,"export revision");
        PhotoCatalog.require(e.format!=null&&(e.format.isEmpty()||e.format.equals("pdf")||e.format.equals("xlsx")||e.format.equals("print")),"export format");
        PhotoCatalog.require(e.pinToken!=null,"export pin token");if(!e.pinToken.isEmpty())PhotoCatalog.uuid(e.pinToken);PhotoCatalog.size(e.options,20);for(Map.Entry<String,String> o:e.options.entrySet()){PhotoCatalog.text(o.getKey(),80);PhotoCatalog.require(o.getValue()!=null&&o.getValue().length()<=512,"option length");}
        PhotoCatalog.size(e.jobCopy.rows,Inventory.MAX_ROWS);PhotoCatalog.require(e.photoVersions!=null&&e.photoVersions.size()==e.jobCopy.rows.size()&&e.bindingRevisions!=null&&e.bindingRevisions.size()==e.jobCopy.rows.size(),"frozen row count");
        PhotoCatalog.size(e.assets,PhotoLimits.MAX_PHOTO_ASSETS);PhotoCatalog c=new PhotoCatalog();c.assets.putAll(e.assets);c.validate(e.revision);Set<String> ids=new LinkedHashSet<>(),refs=new LinkedHashSet<>();int i=0;
        for(Inventory.Row row:e.jobCopy.rows){PhotoCatalog.require(row!=null,"export row");PhotoCatalog.uuid(row.rowId);PhotoCatalog.require(ids.add(row.rowId),"duplicate export row");c.validateBinding(row.binding,e.revision,false);PhotoCatalog.Binding b=row.binding;
            Long rev=e.bindingRevisions.get(row.rowId);PhotoCatalog.require(rev!=null&&rev==(b==null?0:b.bindingRevision),"frozen binding revision");String expected=b==null||b.needsConfirmation?"":b.photoVersionId;PhotoCatalog.require(expected.equals(e.photoVersions.get(i++)),"frozen photo version");if(b!=null)refs.add(b.photoVersionId);
        }
        PhotoCatalog.require(refs.equals(e.assets.keySet()),"frozen asset references");
        // Use the existing bounded job validator without introducing a second field schema.
        try(DataOutputStream out=new DataOutputStream(new StateCodec.LimitedOutputStream(new ByteArrayOutputStream()))){StateCodec.writeJob(out,e.jobCopy);}
    }
    public Set<String> protectedVersions(){return new LinkedHashSet<>(assets.keySet());}
    public Set<String> renderedVersions(){Set<String> refs=new LinkedHashSet<>();for(int i=0;i<photoVersions.size();i++)if(jobCopy.rows.get(i).total()>0&&!photoVersions.get(i).isEmpty())refs.add(photoVersions.get(i));return refs;}
    /** Reads one photo at a time; an unreadable/corrupt/short source cannot complete output. */
    public void verifyMedia(MediaSource source)throws IOException {
        validate(this);if(source!=null)source.checkReady();for(String hash:renderedVersions()){
            if(source==null)throw new IOException("Missing pinned media source");PhotoCatalog.Asset a=assets.get(hash);MessageDigest digest=MediaStore.digest();long count=0;
            try(InputStream in=source.open(hash)){if(in==null)throw new IOException("Missing export photo");byte[] block=new byte[32768];for(int n;(n=in.read(block))!=-1;){if(n==0){int value=in.read();if(value==-1)break;block[0]=(byte)value;n=1;}if(n>a.byteLength-count)throw new IOException("Export photo length");count+=n;digest.update(block,0,n);}}
            if(count!=a.byteLength||!MediaStore.hex(digest.digest()).equals(hash))throw new IOException("Export photo bytes/hash");
        }
    }
}
