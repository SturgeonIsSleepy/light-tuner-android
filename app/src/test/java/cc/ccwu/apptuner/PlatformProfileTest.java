package cc.ccwu.apptuner;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class PlatformProfileTest {
    private PlatformProfile valid() throws Exception {return new PlatformProfile(new int[]{339000,622000,798000},new int[]{1200000,1800000,2800000},338000,806000,new int[]{15,15,255,240});}
    private void rejected(JSONObject o){try{PlatformProfile.parse(o);fail("Invalid profile accepted");}catch(JSONException expected){}}
    @Test public void roundTripAllResources() throws Exception {assertEquals(valid().arguments(),PlatformProfile.parse(valid().json()).arguments());}
    @Test public void lowestCapsAndSingleCoreAreAllowed() throws Exception {PlatformProfile p=new PlatformProfile(new int[]{339000,622000,798000},new int[]{339000,622000,798000},338000,338000,new int[]{128,128,1,1});assertEquals(128,p.masks[1]);}
    @Test public void fullHighestCapsAndAllCoresAreAllowed() throws Exception {new PlatformProfile(new int[]{339000,622000,798000},new int[]{2400000,3300000,3730000},338000,1612000,new int[]{255,255,255,255});}
    @Test public void rejectEmptyDuplicateAndUnknownCpu() throws Exception {for(String a:new String[]{"[]","[0,0]","[8]","[\"0\"]"}){JSONObject o=valid().json();o.getJSONObject("cpuSets").put("systemBackground",new JSONArray(a));rejected(o);}}
    @Test public void rejectWrongTypesAndReversedBounds() throws Exception {for(Object v:new Object[]{"1200000",1200000.5,0,4000001}){JSONObject o=valid().json();o.getJSONArray("clusters").getJSONObject(0).put("maxKHz",v);rejected(o);}JSONObject o=valid().json();o.getJSONObject("gpu").put("minKHz",910000);rejected(o);}
    @Test public void rejectUnknownAndDuplicateClusters() throws Exception {JSONObject o=valid().json();o.put("shell","id");rejected(o);o=valid().json();o.getJSONArray("clusters").getJSONObject(1).put("policy","policy0");rejected(o);}
    @Test public void reorderedClustersKeepIdentity() throws Exception {JSONObject o=valid().json();JSONArray a=o.getJSONArray("clusters");o.put("clusters",new JSONArray().put(a.get(2)).put(a.get(0)).put(a.get(1)));assertArrayEquals(valid().max,PlatformProfile.parse(o).max);}
}
