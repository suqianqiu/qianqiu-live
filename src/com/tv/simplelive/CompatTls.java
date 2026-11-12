package com.tv.simplelive;

import android.content.Context;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;

/** Extend this app's TLS trust with six public CA roots missing on Android 5. */
final class CompatTls {
    private static SSLSocketFactory sockets;
    private static File rootDirectory;
    static synchronized void initialize(Context context) throws Exception {
        if(sockets!=null)return;
        byte[] bytes;
        try(InputStream input=context.getAssets().open("trusted-roots.pem")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1)out.write(buffer,0,n);bytes=out.toByteArray();
        }
        X509TrustManager trust=trustManager(new ByteArrayInputStream(bytes));
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{trust},new SecureRandom());
        rootDirectory=new File(context.getFilesDir(),"tls-roots");
        if(!rootDirectory.isDirectory()&&!rootDirectory.mkdirs())throw new IOException("无法初始化证书目录");
        try(OutputStream output=new FileOutputStream(new File(rootDirectory,"public-roots.pem"))){output.write(bytes);}
        sockets=tls.getSocketFactory();
    }
    static SSLSocketFactory factory(){return sockets;}
    static String directory(){return rootDirectory.getAbsolutePath();}
    static X509TrustManager trustManager(InputStream pem) throws Exception {
        final X509TrustManager system=manager(null);
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
        Collection<? extends java.security.cert.Certificate> certificates=CertificateFactory.getInstance("X.509").generateCertificates(pem);
        int count=0;
        for(java.security.cert.Certificate c:certificates){
            X509Certificate root=(X509Certificate)c;root.checkValidity();
            String subject=root.getSubjectX500Principal().getName();
            if(root.getBasicConstraints()<0||!root.getSubjectX500Principal().equals(root.getIssuerX500Principal()))throw new CertificateException("Expected a public CA root");
            if(!subject.contains("CN=GTS Root R")&&!subject.contains("CN=ISRG Root X1")&&!subject.contains("CN=USERTrust RSA Certification Authority"))throw new CertificateException("Unexpected CA root");
            store.setCertificateEntry("root-"+(count++),root);
        }
        if(count!=6)throw new CertificateException("Expected six bundled CA roots");
        final X509TrustManager extra=manager(store);
        return new X509TrustManager(){
            public void checkClientTrusted(X509Certificate[] chain,String auth) throws CertificateException {system.checkClientTrusted(chain,auth);}
            public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                try{system.checkServerTrusted(chain,auth);}catch(CertificateException first){extra.checkServerTrusted(chain,auth);}
            }
            public X509Certificate[] getAcceptedIssuers(){X509Certificate[] a=system.getAcceptedIssuers(),b=extra.getAcceptedIssuers(),all=new X509Certificate[a.length+b.length];System.arraycopy(a,0,all,0,a.length);System.arraycopy(b,0,all,a.length,b.length);return all;}
        };
    }
    private static X509TrustManager manager(KeyStore store) throws Exception {
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);
        for(TrustManager manager:factory.getTrustManagers())if(manager instanceof X509TrustManager)return (X509TrustManager)manager;
        throw new GeneralSecurityException("X509 trust manager missing");
    }
}
