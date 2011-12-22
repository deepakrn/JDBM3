import java.io.IOException;

import jdbm.*;


/** 
 * 
 * This examples generates huge map of data. 
 * It inserts 10 000 000 records, it takes about 10 minutes to finish. 
 * 
 * @author Jan Kotek
 *
 */
public class HugeData {
	public static void main(String[] args) throws IOException {

		/** open db */
        DB db = new DBMaker("hugedata").build();
        PrimaryTreeMap<Long, String> m = db.createTreeMap("hugemap");
        
        /** insert 1e7 records */
        for(long i = 0;i<1e8;i++){
        	m.put(i, "aa"+i);        
        	if(i%1e5==0){
        		/** Commit periodically, otherwise program would run out of memory */         		 
        		db.commit();
        		System.out.println(i);        		
        	}
        		
        }
        
        db.commit();
        db.close();
        System.out.println("DONE");
        
	}
}
