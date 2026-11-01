/*
 * Copyright 2025 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.common.config.interfaces.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that secret configuration is properly set up:
 * - When isSecret=true: credentialVaultId must be present, parameterValue/configValue should be null
 * - When isSecret=false: parameterValue/configValue must be present, credentialVaultId should be null
 * 
 * This annotation should be applied at the class level on DTOs that have secret configuration.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {
    SecretConfigurationValidatorImpl.class
})
@Documented
public @interface ValidSecretConfiguration {
    
    String message() default "Invalid secret configuration: when isSecret=true, credentialVaultId is required; when isSecret=false, value field is required";
    
    Class<?>[] groups() default {};
    
    Class<? extends Payload>[] payload() default {};
    
    /**
     * Name of the field that contains the secret flag (default: "isSecret")
     */
    String secretFlagField() default "isSecret";
    
    /**
     * Name of the field that contains the direct value (e.g., "parameterValue" or "configValue")
     */
    String valueField();
    
    /**
     * Name of the field that contains the vault ID reference (default: "credentialVaultId")
     */
    String vaultIdField() default "credentialVaultId";
}

