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

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;

/**
 * Validator for {@link ValidSecretConfiguration} annotation.
 * 
 * Ensures that:
 * 1. When isSecret=true: credentialVaultId must be present and not blank
 * 2. When isSecret=false: the value field (parameterValue/configValue) must be present and not blank
 * 3. Security warning: if isSecret=true but value field is also set, it's a potential security issue
 */
@Slf4j
public class SecretConfigurationValidatorImpl implements ConstraintValidator<ValidSecretConfiguration, Object> {
    
    private String secretFlagField;
    private String valueField;
    private String vaultIdField;
    
    @Override
    public void initialize(ValidSecretConfiguration annotation) {
        this.secretFlagField = annotation.secretFlagField();
        this.valueField = annotation.valueField();
        this.vaultIdField = annotation.vaultIdField();
    }
    
    @Override
    public boolean isValid(Object object, ConstraintValidatorContext context) {
        if (object == null) {
            return true; // null objects are valid (use @NotNull for null checks)
        }
        
        try {
            // Get field values using reflection
            Boolean isSecret = getFieldValue(object, secretFlagField, Boolean.class);
            String value = getFieldValue(object, valueField, String.class);
            String vaultId = getFieldValue(object, vaultIdField, String.class);
            
            // If isSecret is null, treat as false (default behavior)
            if (isSecret == null) {
                isSecret = false;
            }
            
            context.disableDefaultConstraintViolation();
            
            if (Boolean.TRUE.equals(isSecret)) {
                // Secret configuration: must have vaultId, should not have value
                return validateSecretConfiguration(vaultId, value, context);
            } else {
                // Non-secret configuration: must have value, should not have vaultId
                return validateNonSecretConfiguration(value, vaultId, context);
            }
            
        } catch (Exception e) {
            log.error("Error validating secret configuration", e);
            context.buildConstraintViolationWithTemplate(
                "Internal validation error: " + e.getMessage()
            ).addConstraintViolation();
            return false;
        }
    }
    
    /**
     * Validates secret configuration (isSecret=true)
     */
    private boolean validateSecretConfiguration(String vaultId, String value, ConstraintValidatorContext context) {
        boolean isValid = true;
        
        // credentialVaultId is required
        if (vaultId == null || vaultId.isBlank()) {
            context.buildConstraintViolationWithTemplate(
                "When isSecret=true, credentialVaultId is required and cannot be blank"
            )
            .addPropertyNode(vaultIdField)
            .addConstraintViolation();
            isValid = false;
        }
        
        // parameterValue/configValue must be null for security
        if (value != null && !value.isBlank()) {
            log.warn("SECURITY WARNING: {} is set even though isSecret=true. " +
                     "This is a security risk as credentials should only be stored in the vault.",
                     valueField);

            context.buildConstraintViolationWithTemplate(
                String.format("When isSecret=true, %s must be null. Credentials must only be stored in the security-vault", valueField)
            )
            .addPropertyNode(valueField)
            .addConstraintViolation();
            isValid = false;
        }
        
        return isValid;
    }
    
    /**
     * Validates non-secret configuration (isSecret=false)
     */
    private boolean validateNonSecretConfiguration(String value, String vaultId, ConstraintValidatorContext context) {
        boolean isValid = true;
        
        // parameterValue/configValue is required
        if (value == null || value.isBlank()) {
            context.buildConstraintViolationWithTemplate(
                String.format("When isSecret=false, %s is required and cannot be blank", valueField)
            )
            .addPropertyNode(valueField)
            .addConstraintViolation();
            isValid = false;
        }
        
        // credentialVaultId must be null
        if (vaultId != null && !vaultId.isBlank()) {
            context.buildConstraintViolationWithTemplate(
                "When isSecret=false, credentialVaultId must be null"
            )
            .addPropertyNode(vaultIdField)
            .addConstraintViolation();
            isValid = false;
        }
        
        return isValid;
    }
    
    /**
     * Gets a field value from an object using reflection
     */
    @SuppressWarnings("unchecked")
    private <T> T getFieldValue(Object object, String fieldName, Class<T> fieldType) throws Exception {
        Field field = findField(object.getClass(), fieldName);
        if (field == null) {
            throw new IllegalArgumentException(
                String.format("Field '%s' not found in class %s", fieldName, object.getClass().getName())
            );
        }
        
        field.setAccessible(true);
        Object value = field.get(object);
        
        if (value == null) {
            return null;
        }
        
        if (!fieldType.isAssignableFrom(value.getClass())) {
            throw new IllegalArgumentException(
                String.format("Field '%s' is not of type %s", fieldName, fieldType.getName())
            );
        }
        
        return (T) value;
    }
    
    /**
     * Finds a field in a class or its superclasses
     */
    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> currentClass = clazz;
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                currentClass = currentClass.getSuperclass();
            }
        }
        return null;
    }
}

