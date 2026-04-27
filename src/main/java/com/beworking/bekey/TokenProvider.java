package com.beworking.bekey;

/**
* Supplies a current valid Akiles bearer token.
* Two implementations: StaticTokenProvider (prod, static API key) and                          
* RefreshingOAuthTokenProvider (staging/dev, OAuth refresh flow).                              
*/ 

public interface TokenProvider {

    String token();
}