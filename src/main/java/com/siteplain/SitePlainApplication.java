package com.siteplain;

import com.siteplain.config.DataProperties;
import com.siteplain.config.GeocoderProperties;
import com.siteplain.config.LimitsProperties;
import com.siteplain.config.SecurityProperties;
import com.siteplain.config.SiteProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        DataProperties.class,
        GeocoderProperties.class,
        LimitsProperties.class,
        SecurityProperties.class,
        SiteProperties.class
})
public class SitePlainApplication {

    public static void main(String[] args) {
        SpringApplication.run(SitePlainApplication.class, args);
    }
}
