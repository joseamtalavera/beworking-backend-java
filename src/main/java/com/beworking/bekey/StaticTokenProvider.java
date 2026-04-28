package com.beworking.bekey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**                                                                                             
* Production token provider: returns a static API key from configuration.                    
* Active when akiles.auth.mode is "static" or unset.                                           
*/  

@Component
@ConditionalOnProperty(name= "akiles.auth.mode", havingValue = "static", matchIfMissing = true)
class StaticTokenProvider implements TokenProvider {
    private final String apiToken;

    StaticTokenProvider(@Value("${akiles.api.token:}") String apiToken) {
        this.apiToken = apiToken;
    }

    @Override
    public String token() {
        return apiToken;
    }
}