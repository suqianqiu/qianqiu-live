package updater.qa;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import java.io.*;import java.lang.reflect.*;import java.util.concurrent.*;

/** Exercises the production update page with a signed deterministic release fixture. */
public final class UpdateUi extends UpdateChecks {
    @Override public void onStart(){Bundle result=new Bundle();Object client=null;Activity page=null;
        try{
            load();String pkg=context.getPackageName();String version=options.getString("nextVersion");
            TestContext isolated=new TestContext(context);client=model(isolated,new Fake(candidate,metadata(candidate,version)));check(client,true);
            Field singleton=type.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,client);
            Intent intent=new Intent().setClassName(pkg,pkg+".UpdateActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);page=startActivitySync(intent);waitForIdleSync();SystemClock.sleep(400);
            Button primary=(Button)field(page,"primary"),refresh=(Button)field(page,"refresh");android.graphics.Bitmap initial=getUiAutomation().takeScreenshot();try(OutputStream o=new FileOutputStream(new File(context.getExternalFilesDir("qa"),"initial.png"))){initial.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);}initial.recycle();require(primary.hasFocus(),"update action receives initial remote focus; window="+primary.hasWindowFocus()+" enabled="+primary.isEnabled()+" touch="+primary.isInTouchMode()+" focused="+page.getCurrentFocus());sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitForIdleSync();require(refresh.hasFocus(),"remote can focus manual refresh");sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);waitForIdleSync();require(primary.hasFocus(),"remote returns to primary action");
            runOnMainSync(primary::performClick);waitIdle(client);waitForIdleSync();require(phase(client).equals("READY"),"page downloads and verifies signed release");require(primary.getText().toString().equals("立即安装"),"verified download exposes install action");
            android.graphics.Bitmap b=getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir("qa"),"update-page.png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}b.recycle();
            if(options.getString("install","false").equals("true")){
                context.getSharedPreferences("update-data-preservation-qa",0).edit().putString("marker","retain-after-upgrade").commit();
                require((Boolean)call(type,client,"canInstall",new Class[]{}),"installation permission enabled");runOnMainSync(primary::performClick);
                SystemClock.sleep(1000);try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir("qa"),"install-started.txt"))){out.write("system confirmation required".getBytes("UTF-8"));}
                // Host confirms the real Android installer. Successful replacement terminates this process.
                SystemClock.sleep(120000);throw new AssertionError("System installation did not replace app");
            }
            Activity close=page;runOnMainSync(close::finish);waitForIdleSync();require(page.isFinishing(),"Back/finish closes update page without launching installer");result.putString("result","PASS");
        }catch(Throwable e){result.putString("result","FAIL");result.putString("error",e.toString());}
        finally{if(client!=null)try{((ExecutorService)field(client,"work")).shutdownNow();}catch(Exception ignored){}}
        result.putInt("checks",checks);finish("PASS".equals(result.getString("result"))?-1:0,result);
    }
}
