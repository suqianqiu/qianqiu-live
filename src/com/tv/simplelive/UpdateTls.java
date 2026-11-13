package com.tv.simplelive;

import android.content.Context;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;

/** Public Sectigo roots for old Android update clients; normal hostname checks remain enabled. */
final class UpdateTls {
    private static SSLSocketFactory sockets;
    private static final Set<String> ROOTS=new HashSet<>(Arrays.asList(
        "4ff460d54b9c86dabfbcfc5712e0400d2bed3fbc4d4fbdaa86e06adcd2a9ad7a",
        "c90f26f0fb1b4018b22227519b5ca2b53e2ca5b3be5cf18efe1bef47380c5383",
        "7bb647a62aeeac88bf257aa522d01ffea395e0ab45c73f93f65654ec38f25a06"));
    static synchronized SSLSocketFactory factory(Context context)throws Exception{
        if(sockets==null)try(InputStream baseline=context.getAssets().open("trusted-roots.pem");InputStream extra=context.getAssets().open("update-roots.pem")){
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{manager(CompatTls.trustManager(baseline),extra)},new SecureRandom());sockets=tls.getSocketFactory();
        }
        return sockets;
    }
    static X509TrustManager manager(final X509TrustManager baseline,InputStream input)throws Exception{
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);Set<String> seen=new HashSet<>();
        for(java.security.cert.Certificate item:CertificateFactory.getInstance("X.509").generateCertificates(input)){
            X509Certificate root=(X509Certificate)item;root.checkValidity();root.verify(root.getPublicKey());
            StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(root.getEncoded()))hex.append(String.format(Locale.US,"%02x",b&255));String hash=hex.toString();
            if(root.getBasicConstraints()<0||!root.getSubjectX500Principal().equals(root.getIssuerX500Principal())||!ROOTS.contains(hash)||!seen.add(hash))throw new CertificateException("Unexpected update CA root");
            store.setCertificateEntry(hash,root);
        }
        if(!seen.equals(ROOTS))throw new CertificateException("Incomplete update CA roots");
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);X509TrustManager found=null;
        for(TrustManager manager:factory.getTrustManagers())if(manager instanceof X509TrustManager)found=(X509TrustManager)manager;
        if(found==null)throw new GeneralSecurityException("X509 trust manager missing");final X509TrustManager additional=found;
        return new X509TrustManager(){
            public void checkClientTrusted(X509Certificate[] chain,String auth)throws CertificateException{baseline.checkClientTrusted(chain,auth);}
            public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException{try{baseline.checkServerTrusted(chain,auth);}catch(CertificateException unavailable){additional.checkServerTrusted(chain,auth);}}
            public X509Certificate[] getAcceptedIssuers(){X509Certificate[] a=baseline.getAcceptedIssuers(),b=additional.getAcceptedIssuers(),all=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,all,a.length,b.length);return all;}
        };
    }
}
