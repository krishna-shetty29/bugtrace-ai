package com.bugtrace.worker;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Origin chosen by the operator at startup, never by queue payloads. */
public final class BackendOrigin {
    private BackendOrigin() {}
    public static String normalize(String value) {
        if(value==null || value.isBlank() || !value.equals(value.trim()) || !value.matches("(?i)https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?/?") || value.contains("\\"))
            throw new IllegalArgumentException("Configure an HTTPS backend origin or localhost HTTP");
        URI uri;
        try { uri=URI.create(value); } catch(IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid backend origin");
        }
        String host=uri.getHost(),scheme=uri.getScheme();
        if(host==null || scheme==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null
            || uri.getRawFragment()!=null || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
            || uri.getPort()==0 || uri.getPort()>65535)
            throw new IllegalArgumentException("Backend must be an origin without credentials, path, query or fragment");
        host=host.toLowerCase(Locale.ROOT);scheme=scheme.toLowerCase(Locale.ROOT);
        if(!scheme.equals("https") && !(scheme.equals("http") && Set.of("localhost","127.0.0.1").contains(host)))
            throw new IllegalArgumentException("Remote backend requires HTTPS");
        int port=uri.getPort();
        if(port==(scheme.equals("https")?443:80))port=-1;
        return scheme+"://"+host+(port==-1?"":":"+port);
    }
}
