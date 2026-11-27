package com.insurai.platform.core.document.adapter;

import com.insurai.platform.core.shared.config.ClaimPilotProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class S3Config {

    @Bean(destroyMethod = "close")
    S3ObjectStore objectStore(ClaimPilotProperties props) {
        return new S3ObjectStore(props.storage());
    }
}
