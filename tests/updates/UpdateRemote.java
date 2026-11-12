package updater.qa;
import android.os.*;
/** Checks the real public repository using the production TLS/network implementation. */
public final class UpdateRemote extends TestBase {
    @Override public void onStart(){Bundle result=new Bundle();try{
        context=getTargetContext();Class<?> type=Class.forName(context.getPackageName()+".AppUpdate");Object client=call(type,null,"get",new Class[]{android.content.Context.class},context);
        call(type,client,"check",new Class[]{boolean.class},true);long until=SystemClock.elapsedRealtime()+100000;
        while(SystemClock.elapsedRealtime()<until){Object state=call(type,client,"state",new Class[]{});if(!(Boolean)field(client,"working"))break;SystemClock.sleep(100);}
        Object state=call(type,client,"state",new Class[]{});String phase=field(state,"phase").toString();require(phase.equals("CURRENT"),"real repository check completes, state="+phase);require(!(Boolean)field(state,"newer"),"older published release does not offer downgrade");require((Long)field(state,"checked")>0,"successful remote check saved");result.putString("result","PASS");
    }catch(Throwable e){result.putString("result","FAIL");result.putString("error",e.toString());}result.putInt("checks",checks);finish("PASS".equals(result.getString("result"))?-1:0,result);}
}
