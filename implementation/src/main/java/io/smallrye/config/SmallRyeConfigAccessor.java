/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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

package io.smallrye.config;

import static io.smallrye.config.SmallRyeConfig.propertyNotFound;

import java.io.Serializable;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.microprofile.config.ConfigAccessor;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

public final class SmallRyeConfigAccessor<T> implements Serializable, ConfigAccessor<T> {
    private static final long serialVersionUID = -5314225642883178068L;

    final String propertyName;
    final SmallRyeConfig config;
    final Converter<T> converter;
    final String stringDefaultValue;
    final T defaultValue;

    SmallRyeConfigAccessor(SmallRyeConfig config, String propertyName) {
        this.propertyName = propertyName;
        this.config = config;
        this.converter = null;
        this.stringDefaultValue = null;
        this.defaultValue = null;
    }

    SmallRyeConfigAccessor(String propertyName, SmallRyeConfigAccessor<T> other) {
        this.propertyName = propertyName;
        this.config = other.config;
        this.converter = other.converter;
        this.stringDefaultValue = other.stringDefaultValue;
        this.defaultValue = other.defaultValue;
    }

    SmallRyeConfigAccessor(SmallRyeConfigAccessor<?> other, Converter<T> converter) {
        this.propertyName = other.propertyName;
        this.config = other.config;
        this.converter = converter;
        this.stringDefaultValue = other.stringDefaultValue;
        this.defaultValue = null;
    }

    SmallRyeConfigAccessor(SmallRyeConfigAccessor<T> other, String stringDefaultValue) {
        this.propertyName = other.propertyName;
        this.config = other.config;
        this.converter = other.converter;
        this.stringDefaultValue = stringDefaultValue;
        this.defaultValue = null;
    }

    SmallRyeConfigAccessor(SmallRyeConfigAccessor<T> other, T defaultValue) {
        this.propertyName = other.propertyName;
        this.config = other.config;
        this.converter = other.converter;
        this.stringDefaultValue = null;
        this.defaultValue = defaultValue;
    }

    public SmallRyeConfig getConfig() {
        return config;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public ConfigAccessor<T> forPropertyName(final String propertyName) {
        return new SmallRyeConfigAccessor<>(propertyName, this);
    }

    public T getValue() throws IllegalArgumentException, NoSuchElementException, IllegalStateException {
        final Converter<T> converter = this.converter;
        if (converter == null) {
            throw notAssociated();
        }
        final String propertyName = this.propertyName;
        for (ConfigSource configSource : config.getConfigSources()) {
            String value = configSource.getValue(propertyName);
            if (value != null) {
                final T converted = converter.convert(value);
                if (converted == null) {
                    throw propertyNotFound(propertyName);
                }
                return converted;
            }
        }
        if (defaultValue != null) {
            return defaultValue;
        }
        String stringDefaultValue = this.stringDefaultValue;
        if (stringDefaultValue == null) {
            stringDefaultValue = "";
        }
        final T converted = converter.convert(stringDefaultValue);
        if (converted == null) {
            throw propertyNotFound(propertyName);
        }
        return converted;
    }

    public ConfigAccessor<Optional<T>> optional() throws IllegalStateException {
        final Converter<T> converter = this.converter;
        if (converter == null) {
            throw notAssociated();
        }
        if (converter instanceof Converters.OptionalConverter) {
            throw new IllegalStateException("Accessor is already optional");
        }
        return new SmallRyeConfigAccessor<>(this, Converters.newOptionalConverter(converter));
    }

    public <U> ConfigAccessor<U> convertedWith(final Converter<U> converter) throws IllegalStateException {
        Objects.requireNonNull(converter, "converter");
        if (defaultValue != null) {
            throw new IllegalStateException("Already associated with a type-specific default value");
        }
        return new SmallRyeConfigAccessor<U>(this, converter);
    }

    public <U> ConfigAccessor<U> convertedForType(final Class<U> clazz) throws IllegalStateException {
        Objects.requireNonNull(clazz, "clazz");
        final Converter<U> converter = config.requireConverter(clazz);
        if (converter == null) {
            throw noConverter(clazz);
        }
        return convertedWith(converter);
    }

    public ConfigAccessor<T> withDefault(final T defaultValue) throws IllegalStateException {
        final Converter<T> converter = this.converter;
        if (converter == null) {
            throw notAssociated();
        }
        if (converter instanceof Converters.OptionalConverter) {
            throw new IllegalStateException("Accessor is optional");
        }
        return new SmallRyeConfigAccessor<>(this, defaultValue);
    }

    public ConfigAccessor<T> withStringDefault(final String defaultValue) {
        Objects.requireNonNull(defaultValue, "defaultValue");
        return new SmallRyeConfigAccessor<>(this, defaultValue);
    }

    public ConfigAccessor<T> withoutDefault() {
        return new SmallRyeConfigAccessor<>(this, (String) null);
    }

    Object writeReplace() {
        return new Ser<>(this);
    }

    static IllegalStateException notAssociated() {
        return new IllegalStateException("Configuration accessor is not associated with a type or converter");
    }

    static IllegalStateException noConverter(final Class<?> clazz) {
        return new IllegalStateException("No converter is available for " + clazz);
    }

    static final class Ser<T> implements Serializable {
        private static final long serialVersionUID = 9147598493320843713L;

        // short names to reduce serialization overhead

        final String pn;
        final SmallRyeConfig c;
        final Converter<T> cv;
        final String sd;
        final T d;

        Ser(final SmallRyeConfigAccessor<T> accessor) {
            this.pn = accessor.propertyName;
            this.c = accessor.config;
            this.cv = accessor.converter;
            this.sd = accessor.stringDefaultValue;
            this.d = accessor.defaultValue;
        }

        Object readResolve() {
            ConfigAccessor<T> accessor = Objects.requireNonNull(this.c, "config")
                    .getAccessor(pn)
                    .convertedWith(cv);
            if (sd != null) {
                if (d != null) {
                    throw new IllegalArgumentException("Both a string and typed default value were deserialized");
                }
                accessor = accessor.withStringDefault(sd);
            } else if (d != null) {
                accessor = accessor.withDefault(d);
            }
            return accessor;
        }
    }
}
