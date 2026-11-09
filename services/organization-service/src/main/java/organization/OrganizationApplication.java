package organization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * organization-service: users, teams, memberships and category routing. Users list teams through
 * {@link organization.controller.OrganizationController}; the other services read organization data through
 * {@link organization.controller.InternalOrganizationController}. Publishes no events (ADR D1).
 */
@SpringBootApplication
public class OrganizationApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrganizationApplication.class, args);
    }
}
