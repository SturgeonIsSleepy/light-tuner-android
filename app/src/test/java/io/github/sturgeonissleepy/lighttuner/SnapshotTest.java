package io.github.sturgeonissleepy.lighttuner;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;
public class SnapshotTest {
    private PlatformController.Snapshot device(boolean match) throws Exception {return new PlatformController.Snapshot("policy0=800000;1200000;sugov_ext;339000 800000 1200000 2400000\npolicy4=622000;1800000;sugov_ext;622000 1600000 1800000 3300000\npolicy7=798000;2800000;sugov_ext;798000 2000000 2800000 3730000\ngpu=338000;806000;338000 624000 780000 806000 1612000\nbackground=15\nsystem-background=255\nforeground=255\ntop-app=240\nphase=active\ntoken=\nremaining=0\nmatch="+(match?"1":"0")+"\ntarget=339000 1200000 622000 1800000 798000 2800000 51 31 15 255 255 240\ntargetGpu=338000;806000\nforegroundApp=com.up366.mobile\n");}
    @Test public void unsupportedDeviceFrequencyRejected() throws Exception {PlatformProfile p=new PlatformProfile(new int[]{339000,622000,798000},new int[]{1200001,1800000,2800000},338000,806000,new int[]{15,15,255,240});try{device(true).validate(p);fail("Unsupported cap accepted");}catch(IOException expected){}}
    @Test public void targetIsNotReplacedByLaunchBoostReading() throws Exception {assertEquals(800000,device(false).current().min[0]);assertEquals(339000,device(false).intent().min[0]);}
    @Test public void mismatchDoesNotClaimExternalModificationAndClearsOnNextRead() throws Exception {assertTrue(device(false).status(0).contains("自动复核"));assertFalse(device(false).status(3).contains("外部"));assertTrue(device(true).status(3).contains("一致"));}
    @Test public void foregroundPackageDoesNotOverwriteForegroundCpuMask() throws Exception {assertEquals(255,device(true).masks[2]);assertEquals("com.up366.mobile",device(true).foreground);}
}