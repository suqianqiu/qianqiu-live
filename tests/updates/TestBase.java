package updater.qa;
import android.app.*;import android.content.*;import android.os.*;import java.io.*;import java.lang.reflect.*;import java.util.*;
public class TestBase extends Instrumentation {
    Context context;int checks;ArrayList<String> cases=new ArrayList<>();
    Object call(Class<?> c,Object target,String name,Class<?>[] types,Object...args)throws Exception{Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
    Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    Object make(Class<?> c,Class<?>[] types,Object...args)throws Exception{Constructor<?> ctor=c.getDeclaredConstructor(types);ctor.setAccessible(true);return ctor.newInstance(args);}
    void require(boolean b,String name){if(!b)throw new AssertionError(name);checks++;cases.add(name);}
    void remove(File p){File[] files=p.listFiles();if(files!=null)for(File f:files)remove(f);p.delete();}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
}
