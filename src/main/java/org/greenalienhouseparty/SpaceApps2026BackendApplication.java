package org.greenalienhouseparty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the Space Apps 2026 Spring Boot application.
 */
@SpringBootApplication
public class SpaceApps2026BackendApplication {

    private static final Logger log = LoggerFactory.getLogger(SpaceApps2026BackendApplication.class);

    /**
     * Launches the embedded web server and initializes the Spring application context.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        log.trace("Entering main()");
        try {
            SpringApplication.run(SpaceApps2026BackendApplication.class, args);
        } finally {
            log.trace("Exiting main()");
        }
    }

}
