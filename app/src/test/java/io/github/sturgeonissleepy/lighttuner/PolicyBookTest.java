package io.github.sturgeonissleepy.lighttuner;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class PolicyBookTest {
    private PlatformProfile p(int cap) throws Exception {return new PlatformProfile(new int[]{339000,622000,798000},new int[]{cap,1800000,2800000},338000,806000,new int[]{15,15,255,240});}
    private PolicyBook book() throws Exception {return new PolicyBook(new PlatformProfile[]{p(800000),p(1200000),p(2400000)});}
    private void rejected(String text){try{PolicyBook.parse(text);fail("Invalid book accepted");}catch(JSONException expected){}}
    @Test public void appPresetAndGlobalFallback() throws Exception {PolicyBook b=book();PolicyBook.Rule r=new PolicyBook.Rule();r.preset="powersave";b.apps.put("com.up366.mobile",r);assertEquals(800000,b.forApp("com.up366.mobile").max[0]);assertEquals(1200000,b.forApp("com.other.app").max[0]);r.enabled=false;assertEquals(1200000,b.forApp("com.up366.mobile").max[0]);}
    @Test public void independentProfileOverridesPresetAndRoundTrips() throws Exception {PolicyBook b=book();PolicyBook.Rule r=new PolicyBook.Rule();r.custom=p(1600000);b.apps.put("com.up366.mobile",r);PolicyBook copy=PolicyBook.parse(b.json().toString());assertEquals(1600000,copy.forApp("com.up366.mobile").max[0]);assertTrue(copy.rootRules().contains("com.up366.mobile 339000 1600000"));}
    @Test public void editingSharedPresetUpdatesMatchingApps() throws Exception {PolicyBook b=book();PolicyBook.Rule r=new PolicyBook.Rule();r.preset="powersave";b.apps.put("com.test.app",r);b.presets[0]=p(1000000);assertEquals(1000000,b.forApp("com.test.app").max[0]);}
    @Test public void rejectsDuplicateProtectedAndInjectionPackages() throws Exception {for(String pkg:new String[]{"cc.ccwu.apptuner","com.android.systemui","com.a;id","$(id)"}){JSONObject o=book().json();o.getJSONArray("applications").put(new JSONObject().put("packageName",pkg).put("enabled",true).put("preset","balanced"));rejected(o.toString());}PolicyBook b=book();b.apps.put("com.test.app",new PolicyBook.Rule());JSONObject o=b.json();o.getJSONArray("applications").put(o.getJSONArray("applications").get(0));rejected(o.toString());}
    @Test public void rejectsUnknownTypesModesAndTrailingContent() throws Exception {JSONObject o=book().json();o.put("globalPreset","turbo");rejected(o.toString());o=book().json();o.put("shell","id");rejected(o.toString());rejected(book().json()+" {}");}
    @Test public void disabledRulesAreNotWrittenToRootMap() throws Exception {PolicyBook b=book();PolicyBook.Rule r=new PolicyBook.Rule();r.enabled=false;b.apps.put("com.test.app",r);assertFalse(b.rootRules().contains("com.test.app"));}
    @Test public void distributedExampleParsesAndResolvesTianxue() throws Exception {String text=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../examples/mt6991-policy-book.json")),java.nio.charset.StandardCharsets.UTF_8);PolicyBook b=PolicyBook.parse(text);assertEquals(3,b.presets.length);assertNotNull(b.apps.get("com.up366.mobile"));assertEquals(780000,b.forApp("com.up366.mobile").gpuMax);}
}
