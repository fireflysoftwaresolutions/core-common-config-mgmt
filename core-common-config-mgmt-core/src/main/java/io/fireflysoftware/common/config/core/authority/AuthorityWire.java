/*
 * Copyright 2026 Firefly Software Foundation
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
package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

final class AuthorityWire {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(2097152).maxNestingDepth(40).maxNumberLength(20).build());
    }
    private AuthorityWire() {}
    static IllegalArgumentException bad() { return new IllegalArgumentException("Invalid institution authority request"); }
    static String original(byte[] bytes, int max) {
        if (bytes == null || bytes.length == 0 || bytes.length > max) throw bad();
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw bad(); }
    }
    static JsonNode parse(String original) {
        try {
            try (var p = JSON.createParser(original)) {
                JsonToken token;
                while ((token = p.nextToken()) != null) {
                    if (token == JsonToken.VALUE_NUMBER_FLOAT || token == JsonToken.VALUE_NUMBER_INT && !p.getText().matches("0|-?[1-9][0-9]*")) throw bad();
                }
            }
            try (var p = JSON.createParser(original)) {
                JsonNode tree = JSON.readTree(p);
                if (tree == null || p.nextToken() != null) throw bad();
                return tree;
            }
        } catch (Exception e) { throw bad(); }
    }
    static <T> T record(JsonNode node, Class<T> type, Set<String> nullable) {
        try {
            var components = type.getRecordComponents();
            if (!node.isObject() || node.size() != components.length) throw bad();
            var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add);
            if (!names.equals(Arrays.stream(components).map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet()))) throw bad();
            var args = new Object[components.length]; var types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                var c = components[i]; types[i] = c.getType(); var n = node.get(c.getName());
                if (n.isNull()) { if (!nullable.contains(c.getName())) throw bad(); args[i] = null; }
                else args[i] = value(n, c.getGenericType());
            }
            return type.getDeclaredConstructor(types).newInstance(args);
        } catch (Exception e) { throw bad(); }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object value(JsonNode n, Type type) {
        if (type instanceof ParameterizedType p && p.getRawType() == List.class) {
            if (!n.isArray() || n.size() > 3663) throw bad();
            var values = new ArrayList<>(); for (var item : n) { if (item.isNull()) throw bad(); values.add(value(item, p.getActualTypeArguments()[0])); } return List.copyOf(values);
        }
        if (type == String.class) { if (!n.isTextual()) throw bad(); return scalar(n.textValue()); }
        if (type == long.class || type == Long.class) { if (!n.isIntegralNumber() || !n.canConvertToLong()) throw bad(); return n.longValue(); }
        if (type == boolean.class || type == Boolean.class) { if (!n.isBoolean()) throw bad(); return n.booleanValue(); }
        if (type == int.class || type == Integer.class) { if (!n.isIntegralNumber() || !n.canConvertToInt()) throw bad(); return n.intValue(); }
        if (type instanceof Class<?> c && c.isEnum()) { if (!n.isTextual()) throw bad(); return Enum.valueOf((Class)c,n.textValue()); }
        if (type instanceof Class<?> c && c.isRecord()) return record(n,c,Set.of());
        throw bad();
    }
    static String scalar(String s) {
        if (s == null || s.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff || c == 0xfffd || Character.isISOControl(c))) throw bad(); return s;
    }
    static void text(String s, int max) { scalar(s); if (s.isBlank() || s.length() > max) throw bad(); }
    static void uuid(String s) { try { if (s == null || s.length() != 36 || !UUID.fromString(s).toString().equals(s) || s.equals("00000000-0000-0000-0000-000000000000")) throw bad(); } catch (Exception e) { throw bad(); } }
    static void digest(String s) { if (s == null || !s.matches("[0-9a-f]{64}")) throw bad(); }
    static String token(Object value) {
        if (value == null) return "N;";
        if (value instanceof Boolean b) return b ? "B1;" : "B0;";
        if (value instanceof Long || value instanceof Integer) return "I" + value + ";";
        if (value instanceof Enum<?> e) return token(e.name());
        if (value instanceof String s) { scalar(s); return "S" + s.getBytes(StandardCharsets.UTF_8).length + ":" + s; }
        if (value instanceof List<?> list) return "A" + list.size() + ":" + list.stream().map(AuthorityWire::token).collect(java.util.stream.Collectors.joining());
        if (value.getClass().isRecord()) {
            var components = value.getClass().getRecordComponents(); var b = new StringBuilder("R" + components.length + ":");
            for (var c : components) { try { b.append(token(c.getAccessor().invoke(value))); } catch (ReflectiveOperationException e) { throw bad(); } } return b.toString();
        }
        throw bad();
    }
    static String recordToken(Object... values) { return "R" + values.length + ":" + Arrays.stream(values).map(AuthorityWire::token).collect(java.util.stream.Collectors.joining()); }
    static String sha(String canonical) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw bad(); } }
}
