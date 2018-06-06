/**
 * Copyright (C) 2018 Carl-Eric Menzel <cmenzel@wicketbuch.de>
 * and possibly other appendablerepeater contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.wicketbuch.extensions.classpathpreloader;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.google.common.hash.PrimitiveSink;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FilenameMatchProcessor;
import org.apache.wicket.Application;
import org.apache.wicket.core.util.lang.WicketObjects;
import org.apache.wicket.core.util.resource.ClassPathResourceFinder;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.util.file.IResourceFinder;
import org.apache.wicket.util.resource.IResourceStream;
import org.apache.wicket.util.string.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.ListIterator;
import java.util.Set;

@SuppressWarnings("UnstableApiUsage")
public class ClasspathPreloader {
    private static final Logger log = LoggerFactory.getLogger(ClasspathPreloader.class);
    private static final long BLOOM_SIZE = 1_000_000L;

    private static final Funnel<String> FUNNEL = new Funnel<String>() {
        @Override
        public void funnel(String s, PrimitiveSink primitiveSink) {
            primitiveSink.putString(s, Charset.defaultCharset());
        }
    };

    private BloomFilter<String> knownPaths = BloomFilter.create(FUNNEL, BLOOM_SIZE, 0.1);

    private int numberOfFoundPaths = 0;


    public static ClasspathPreloader configure(Application application) {
        return new ClasspathPreloader(application);
    }

    private ClasspathPreloader(Application application) {
        final ListIterator<IResourceFinder> it = application.getResourceSettings().getResourceFinders().listIterator();
        while (it.hasNext()) {
            final IResourceFinder finder = it.next();
            if (finder instanceof ClassPathResourceFinder) {
                it.set(new PrescanningClasspathResourceFinder(((ClassPathResourceFinder) finder)));
            }
        }

        log.info("scanning classpath");
        long startTime = System.currentTimeMillis();
        final Set<String> strings = new HashSet<>();
        new FastClasspathScanner().matchFilenamePattern(".*", new FilenameMatchProcessor() {
            @Override
            public void processMatch(File classpathElt, String relativePath) {
                log.trace("found on classpath: {}", relativePath);
                knownPaths.put(relativePath);
                strings.add(relativePath);
                numberOfFoundPaths++;
            }
        }).scan();

        log.info("scanning complete after {} ms", System.currentTimeMillis() - startTime);
        log.info("found a total of {} files", numberOfFoundPaths);
        log.info("bloom filter using a total of {} bytes", WicketObjects.sizeof(knownPaths));
        log.info("hash set using a total of {} bytes", WicketObjects.sizeof((Serializable) strings));
    }

    class PrescanningClasspathResourceFinder implements IResourceFinder {
        private final ClassPathResourceFinder delegate;
        private final String prefix;

        PrescanningClasspathResourceFinder(ClassPathResourceFinder delegate) {
            this.delegate = delegate;
            // This is evil: PropertyModel handles the reflection for us to access the private
            // field "prefix" in ClassPathResourceFinder. Remove this once Wicket adds
            // a getter.
            String delegatePrefix = new PropertyModel<String>(delegate, "prefix").getObject();
            if (!Strings.isEmpty(delegatePrefix) && !delegatePrefix.endsWith("/")) {
                delegatePrefix += "/";
            }
            this.prefix = delegatePrefix;
        }

        @Override
        public IResourceStream find(Class<?> clazz, String pathname) {
            String prefixedPath = prefix + pathname;
            if (knownPaths.mightContain(prefixedPath)) {
                log.debug("path '{}' exists, delegating", prefixedPath);
                // must pass non-prefixed path here because the delegate will also apply the prefix:
                return delegate.find(clazz, pathname);
            } else {
                log.debug("path '{}' does not exist", prefixedPath);
                return null;
            }
        }
    }
}
