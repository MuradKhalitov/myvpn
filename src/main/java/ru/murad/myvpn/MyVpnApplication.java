package ru.murad.myvpn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MyVpnApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyVpnApplication.class, args);
    }
}
