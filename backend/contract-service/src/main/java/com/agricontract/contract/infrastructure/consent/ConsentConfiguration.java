package com.agricontract.contract.infrastructure.consent;

import com.agricontract.contract.domain.model.consent.ConsentActionType;
import com.agricontract.contract.domain.model.consent.ConsentStatement;
import com.agricontract.contract.domain.model.consent.OperationalConsentStatements;
import com.agricontract.contract.domain.model.consent.ConsentStatements;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(ConsentStatementProperties.class)
public class ConsentConfiguration {

    @Bean
    ConsentStatements consentStatements(ConsentStatementProperties properties) throws IOException {
        Map<SignerRole, ConsentStatement> signContract = new EnumMap<>(SignerRole.class);
        for (SignerRole role : SignerRole.values()) {
            String code = ConsentStatements.signContractCode(role);
            String text = load("consent-statement/" + code + "-" + properties.signContractVersion() + ".txt");
            signContract.put(role, new ConsentStatement(code, properties.signContractVersion(), text,
                    CanonicalTerms.sha256(text)));
        }
        Map<SignerRole, ConsentStatement> cancelActivation = new EnumMap<>(SignerRole.class);
        for (SignerRole role : SignerRole.values()) {
            String code = ConsentStatements.cancelActivationCode(role);
            String text = load("consent-statement/" + code + "-" + properties.cancelActivationVersion() + ".txt");
            cancelActivation.put(role, new ConsentStatement(code, properties.cancelActivationVersion(), text,
                    CanonicalTerms.sha256(text)));
        }
        return new ConsentStatements(signContract, cancelActivation);
    }

    /**
     * The milestone statements (consent design §4, operational tier), one role each: the seller declares the weight
     * (D74). Startup fails if one is not configured or its file is missing.
     */
    @Bean
    OperationalConsentStatements operationalConsentStatements(ConsentStatementProperties properties)
            throws IOException {
        Map<ConsentActionType, SignerRole> actions = Map.of(ConsentActionType.DECLARE_SELLER_WEIGHT, SignerRole.SELLER,
                ConsentActionType.RECORD_BUYER_RECEIPT, SignerRole.BUYER);
        Map<ConsentActionType, ConsentStatement> templates = new EnumMap<>(ConsentActionType.class);
        for (Map.Entry<ConsentActionType, SignerRole> action : actions.entrySet()) {
            String version = properties.milestoneActionVersions().get(action.getKey());
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("contract.consent.milestone-action-versions." + action.getKey()
                        + " is required");
            }
            String code = OperationalConsentStatements.code(action.getKey(), action.getValue());
            // D84: the hash is over the template itself, placeholders ([X]) left as written.
            String text = load("consent-statement/" + code + "-" + version + ".txt");
            templates.put(action.getKey(), new ConsentStatement(code, version, text, CanonicalTerms.sha256(text)));
        }
        return new OperationalConsentStatements(templates);
    }

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The hashed text is the sentence itself: CRLF normalised and surrounding whitespace (file's final newline) removed. */
    static String load(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        }
    }
}
