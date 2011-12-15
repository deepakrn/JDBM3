/*******************************************************************************
 * Copyright 2010 Cees De Groot, Alex Boisvert, Jan Kotek
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package jdbm;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 *  Persistent hashtable implementation for PageManager.
 *  Implemented as an H*Tree structure.
 *
 *  WARNING!  If this instance is used in a transactional context, it
 *            *must* be discarded after a rollback.
 *
 *  @author Alex Boisvert
 */
class HTree<K,V>  extends AbstractPrimaryMap<K,V> implements PrimaryHashMap<K,V>
{

    final Serializer SERIALIZER = new Serializer<Object>() {

        public Object deserialize(DataInput ds2) throws IOException {
            DataInputOutput ds = (DataInputOutput) ds2;
            try{
                int i = ds.readUnsignedByte();
                if(i == SerializationHeader.HTREE_BUCKET){ //is HashBucket?
                    HTreeBucket ret = new HTreeBucket(HTree.this);
                    if(loadValues)
                        ret.readExternal(ds);
                    if(ds.available()!=0)
                        throw new InternalError("bytes left: "+ds.available());
                    return ret;
                }else if( i == SerializationHeader.HTREE_DIRECTORY){
                    HTreeDirectory ret = new HTreeDirectory(HTree.this);
                    ret.readExternal(ds);
                    if(ds.available()!=0)
                        throw new InternalError("bytes left: "+ds.available());
                    return ret;
                }else {
                    throw new InternalError("Wrong HTree header: "+i);
                }
            }catch(ClassNotFoundException e){
                throw new IOException(e);
            }

        }
        public void serialize(DataOutput out, Object obj) throws IOException {
            if(obj instanceof HTreeBucket){
                out.write(SerializationHeader.HTREE_BUCKET);
                HTreeBucket b = (HTreeBucket) obj;
                b.writeExternal(out);
            }else{
                out.write(SerializationHeader.HTREE_DIRECTORY);
                HTreeDirectory n = (HTreeDirectory) obj;
                n.writeExternal(out);
            }
        }
    };



    /**
     * Listeners which are notified about changes in records
     */
    protected RecordListener[] recordListeners = new RecordListener[0];

 /**
     * Serializer used to serialize index keys (optional)
     */
    protected Serializer<K> keySerializer;


    /**
     * Serializer used to serialize index values (optional)
     */
    protected Serializer<V> valueSerializer;
    protected boolean readonly = false;
    private long rootRecid;
    private RecordManager2 recman;
    private long recid;

    /** indicates if values should be loaded during deserialization, set to true during defragmentation */
    private boolean loadValues = true;

    public Serializer<K> getKeySerializer() {
		return keySerializer;
	}

	public Serializer<V> getValueSerializer() {
		return valueSerializer;
	}


    /** cache writing buffer, so it does not have to be allocated on each write */
   AtomicReference<DataInputOutput> writeBufferCache = new AtomicReference<DataInputOutput>();



    /**
     * Create a persistent hashtable.
     */
    public HTree( RecordManager2 recman, long recid, Serializer<K> keySerializer, Serializer<V> valueSerializer )
        throws IOException
    {
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        this.recman = recman;
        this.recid = recid;

        //create new root record
        this.rootRecid = recman.insert(null);
        HTreeDirectory<K,V> root = new HTreeDirectory<K,V>( this, (byte) 0 );
        root.setPersistenceContext(recman, rootRecid);
        this.rootRecid = recman.insert( root, this.SERIALIZER );
    }


    /**
     * Load a persistent hashtable
     */
    public HTree(long rootRecid, Serializer<K> keySerializer, Serializer<V> valueSerializer )
        throws IOException
    {
        this.rootRecid = rootRecid;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
    }

    void setPersistenceContext(RecordManager2 recman, long recid){
        this.recman = recman;
        this.recid = recid;
    }



    
    public synchronized V put(K key, V value) {
	if(readonly)
		throw new UnsupportedOperationException("readonly");

	try {
		if(key == null || value == null)
			throw new NullPointerException("Null key or value");
		V oldVal = get(key);
                getRoot().put(key, value);
                if(oldVal == null){
                        for(RecordListener<K,V> r : recordListeners)
                            r.recordInserted(key,value);
                }else{
                        for(RecordListener<K,V> r : recordListeners)
                            r.recordUpdated(key,oldVal,value);
                }

		return oldVal;
	} catch (IOException e) {
		throw new IOError(e);
	}
    }


    public synchronized V get(Object key)
    {
        try{
	    if(key == null)
		return null;
	    return getRoot().get((K) key);
	}catch (ClassCastException e){
	    return null;
	}catch (IOException e){
	    throw new IOError(e);
        }
    }


    public synchronized V remove(Object key) {
	if(readonly)
		throw new UnsupportedOperationException("readonly");

	try{
		if(key == null)
			return null;
		V oldVal = get((K) key);
		if(oldVal!=null){
                           V val = null;
                           if(recordListeners.length>0)
                                   val = get(key);
                           getRoot().remove(key);
                           if(val!=null)
                                for(RecordListener r : recordListeners)
                                    r.recordRemoved(key,val);
                       }
		return oldVal;
	}catch (ClassCastException e){
		return null;
	}catch (IOException e){
		throw new IOError(e);
	}
    }

    public synchronized boolean containsKey(Object key) {
        if(key == null)
            return false;

        V v = get((K) key);
        return v!=null;
    }

    public synchronized void clear(){
        try{
            Iterator<K> keyIter = keys();
            while(keyIter.hasNext())
                remove(keyIter.next());
        }catch(IOException e){
            throw new IOError(e);
        }
    }


    /**
     * Returns an enumeration of the keys contained in this
     */
    public synchronized Iterator<K> keys()
        throws IOException
    {
        return getRoot().keys();
    }




    public RecordManager2 getRecordManager() {
        return getRoot().getRecordManager();
    }

    /**
     * add RecordListener which is notified about record changes
     * @param listener
     */
    public void addRecordListener(RecordListener<K,V> listener){
        recordListeners = Arrays.copyOf(recordListeners,recordListeners.length+1);
    	recordListeners[recordListeners.length-1]=listener;
    }

    /**
     * remove RecordListener which is notified about record changes
     * @param listener
     */
    public void removeRecordListener(RecordListener<K,V> listener){
        List l = Arrays.asList(recordListeners);
        l.remove(listener);
    	recordListeners = (RecordListener[]) l.toArray(new RecordListener[1]);
    }


    public Set<Entry<K, V>> entrySet() {
            return _entrySet;
    }

    private Set<Entry<K, V>> _entrySet = new AbstractSet<Entry<K,V>>(){

                    protected Entry<K,V> newEntry(K k,V v){
                            return new SimpleEntry<K,V>(k,v){
                                    private static final long serialVersionUID = 978651696969194154L;

                                    public V setValue(V arg0) {
                                            HTree.this.put(getKey(), arg0);
                                            return super.setValue(arg0);
                                    }

                            };
                    }

                    public boolean add(java.util.Map.Entry<K, V> e) {
                            if(readonly)
                                    throw new UnsupportedOperationException("readonly");

                                    if(e.getKey() == null)
                                            throw new NullPointerException("Can not add null key");
                                    if(e.getValue().equals(get(e.getKey())))
                                                    return false;
                                    HTree.this.put(e.getKey(), e.getValue());
                                    return true;
                    }

                    @SuppressWarnings("unchecked")
                    public boolean contains(Object o) {
                            if(o instanceof Entry){
                                    Entry<K,V> e = (java.util.Map.Entry<K, V>) o;

                                    if(e.getKey()!=null && HTree.this.get(e.getKey())!=null)
                                        return true;
                            }
                            return false;
                    }


                    public Iterator<java.util.Map.Entry<K, V>> iterator() {
                            try {
                                final Iterator<K> br = keys();
                                return new Iterator<Entry<K,V>>(){

                                    private Entry<K,V> next;
                                    private K lastKey;
                                    void ensureNext(){
                                            if(br.hasNext()){
                                                   K k = br.next();
                                                   next = newEntry(k,get(k));
                                            }else
                                                   next = null;
                                    }
                                    {
                                            ensureNext();
                                    }



                                    public boolean hasNext() {
                                            return next!=null;
                                    }

                                    public java.util.Map.Entry<K, V> next() {
                                            if(next == null)
                                                    throw new NoSuchElementException();
                                            Entry<K,V> ret = next;
                                            lastKey = ret.getKey();
                                            //move to next position
                                            ensureNext();
                                            return ret;
                                    }

                                    public void remove() {
                                            if(readonly)
                                                    throw new UnsupportedOperationException("readonly");
                                            if(lastKey == null)
                                                    throw new IllegalStateException();

                                                    HTree.this.remove(lastKey);
                                                    lastKey = null;
                                    }};

                            } catch (IOException e) {
                                    throw new IOError(e);
                            }

                    }

                    @SuppressWarnings("unchecked")
                    public boolean remove(Object o) {
                            if(readonly)
                                    throw new UnsupportedOperationException("readonly");

                            if(o instanceof Entry){
                                    Entry<K,V> e = (java.util.Map.Entry<K, V>) o;

                                        //check for nulls
                                            if(e.getKey() == null || e.getValue() == null)
                                                    return false;
                                            //get old value, must be same as item in entry
                                            V v = get(e.getKey());
                                            if(v == null || !e.getValue().equals(v))
                                                    return false;
                                            HTree.this.remove(e.getKey());
                                            return true;
                            }
                            return false;

                    }

                    @Override
                    public int size() {
                            try{
                                    int counter = 0;
                                    Iterator<K> it = keys();
                                    while(it.hasNext()){
                                            it.next();
                                            counter ++;
                                    }
                                    return counter;
                            }catch (IOException e){
                                    throw new IOError(e);
                            }

                    }

            };


    public HTreeDirectory<K, V> getRoot() {
        try{
            HTreeDirectory<K, V> root = (HTreeDirectory<K,V>) recman.fetch( rootRecid, this.SERIALIZER  );
            root.setPersistenceContext( recman, rootRecid );
            return root;
        }catch (IOException e){
           throw new IOError(e);
        }
    }

    public static HTree deserialize(DataInput is) throws IOException, ClassNotFoundException {
        long rootRecid = LongPacker.unpackLong(is);
        Serializer keySerializer = (Serializer) Utils.CONSTRUCTOR_SERIALIZER.deserialize(is);
        Serializer valueSerializer = (Serializer) Utils.CONSTRUCTOR_SERIALIZER.deserialize(is);

        return new HTree(rootRecid, keySerializer, valueSerializer);
    }

    void serialize(DataOutput out) throws IOException {
        LongPacker.packLong(out,rootRecid);
        Utils.CONSTRUCTOR_SERIALIZER.serialize(out,keySerializer);
        Utils.CONSTRUCTOR_SERIALIZER.serialize(out,valueSerializer);
    }

    long getRecid(){
        return recid;
    }

    static void defrag(Long recid, RecordManagerStorage r1, RecordManagerStorage r2) throws IOException {
        try{
        byte[] data = r1.fetchRaw(recid);
        r2.forceInsert(recid,data);
        DataInput in = new DataInputStream(new ByteArrayInputStream(data));
        HTree t = (HTree) r1.defaultSerializer().deserialize(in);
        t.recman = r1;
        t.loadValues = false;

        HTreeDirectory d = t.getRoot();
        if(d!=null){
            r2.forceInsert(t.rootRecid,r1.fetchRaw(t.rootRecid));
            d.defrag(r1,r2);
        }
            
        }catch(ClassNotFoundException e){
            throw new IOError(e);
        }

    }
}

