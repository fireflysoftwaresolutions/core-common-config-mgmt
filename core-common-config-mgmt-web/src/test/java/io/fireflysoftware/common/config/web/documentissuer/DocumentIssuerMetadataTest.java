/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.web.documentissuer;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import io.swagger.v3.core.converter.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DocumentIssuerMetadataTest {
    @Test void actualConverterUsesUniqueDefinitionAndNestedNames(){
        var schema=ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(Definition.class).resolveAsRef(true));
        assertEquals("#/components/schemas/DocumentIssuerDefinition",schema.schema.get$ref());assertTrue(schema.referencedSchemas.containsKey("DocumentIssuerSourceSnapshot"));assertTrue(schema.referencedSchemas.containsKey("DocumentIssuerUse"));
    }
    @Test void actualConverterUsesUniqueCurrentRequestName(){assertEquals("#/components/schemas/DocumentIssuerPublicationReference",ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(PublicationReference.class).resolveAsRef(true)).schema.get$ref());}
}
