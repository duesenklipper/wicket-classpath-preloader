/**
 * Copyright (C) 2018 Carl-Eric Menzel <cmenzel@wicketbuch.de>,
 * Antonia Schmalstieg <antonia.schmalstieg@codecentric.de>,
 * and possibly other classpathpreloader contributors.
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
import java.nio.charset.Charset;
import java.util.ListIterator;

/**
 * Use this if your filesystem or classloading is unusually slow and Wicket's resource loading strategy of trying many different paths, e.g. with locales and
 * variations, is taking too much time. The symptom of this is that the first request with a previously unusued locale takes an unacceptably long time. This
 * can happen e.g. on some IBM systems. <b>Do not use this if your production system is not affected!</b> Also, to keep development turnaround times low,
 * only use it in {@link org.apache.wicket.RuntimeConfigurationType#DEPLOYMENT DEPLOYMENT} mode.
 * <p>
 * <p>
 * To use {@code ClassPathPreloader} in your application, do this in {@link Application#init()}:
 * <pre>
 *     ClassPathPreloader.configure(this);
 * </pre>
 * <strong>Note:</strong> If your application uses custom {@link IResourceFinder}s, initialize them <i>before</i> {@code ClassPathPreloader}.
 * <p><p>
 * {@code ClassPathPreloader} scans the entire classpath to have a list of available resources. All {@link ClassPathResourceFinder}s in the given
 * {@link Application} are wrapped to check against this list before trying to load a resource. This way non-existing paths are not actually accessed, saving
 * a lot of time on systems affected by this. Essentially you will be trading application start time and a constant amount of memory for first-request-served
 * time.
 * <p>
 * To conserve memory and still be fast, we use a {@link BloomFilter} instead of a List of strings to store which paths exist on the classpath. A bloom filter
 * is set up to expect a number of elements and provide an acceptable false positive rate up to that limit. This implementation is currently set to expect up
 * to 1,000,000 classpath entries with a false positive probability of 0.01. This leads to memory consumption of roughly 600KB, regardless of how many entries
 * are stored. A false positive here means that a non-existing path is mistakenly thought to exist and therefore the underlying
 * {@link ClassPathResourceFinder} will try to load it. Bloom filters do not produce false negatives, so no actually existing path will ever be denied.
 */
@SuppressWarnings("UnstableApiUsage")
public class ClassPathPreloader {
    private static final Logger log = LoggerFactory.getLogger(ClassPathPreloader.class);
    private static final long BLOOM_SIZE = 1_000_000L;
    private static final Funnel<String> FUNNEL = new Funnel<String>() {
        @Override
        public void funnel(String s, PrimitiveSink primitiveSink) {
            primitiveSink.putString(s, Charset.defaultCharset());
        }
    };

    private BloomFilter<String> knownPaths = BloomFilter.create(FUNNEL, BLOOM_SIZE, 0.01);

    private int numberOfFoundPaths = 0;


    /**
     * Create a ClassPathPreloader and configure the {@link Application} to use it. Call this after initializing any {@link IResourceFinder}s. Depending on
     * your system and the size of your classpath, this might take some time! It is strongly recommended to only use this on systems that need it, and only
     * in {@link org.apache.wicket.RuntimeConfigurationType#DEPLOYMENT DEPLOYMENT} mode.
     */
    public static ClassPathPreloader configure(Application application) {
        return new ClassPathPreloader(application);
    }

    private ClassPathPreloader(Application application) {
        // find all ClassPathResourceFinders and wrap them
        final ListIterator<IResourceFinder> it = application.getResourceSettings().getResourceFinders().listIterator();
        while (it.hasNext()) {
            final IResourceFinder finder = it.next();
            if (finder instanceof ClassPathResourceFinder) {
                it.set(new PrescanningClasspathResourceFinder(((ClassPathResourceFinder) finder)));
            }
        }

        log.info("scanning classpath");
        long startTime = System.currentTimeMillis();
        // this gives us all files found in all classpath elements, including all dependency jars.
        new FastClasspathScanner().matchFilenamePattern(".*", new FilenameMatchProcessor() {
            @Override
            public void processMatch(File classpathElt, String relativePath) {
                log.trace("found on classpath: {}", relativePath);
                knownPaths.put(relativePath);
                numberOfFoundPaths++;
            }
        }).scan();

        log.info("scanning complete after {} ms", System.currentTimeMillis() - startTime);
        log.info("found a total of {} files", numberOfFoundPaths);
        log.info("bloom filter using a total of {} bytes", WicketObjects.sizeof(knownPaths));
    }

    /**
     * This wraps a ClassPathResourceFinder and only lets it load existing paths.
     */
    class PrescanningClasspathResourceFinder implements IResourceFinder {
        private final ClassPathResourceFinder delegate;
        private final String prefix;

        PrescanningClasspathResourceFinder(ClassPathResourceFinder delegate) {
            this.delegate = delegate;
            // This is evil: PropertyModel handles the reflection for us to access the private
            // field "prefix" in ClassPathResourceFinder. Remove this once Wicket adds
            // a getter. See find() on why we need this.
            String delegatePrefix = new PropertyModel<String>(delegate, "prefix").getObject();
            if (!Strings.isEmpty(delegatePrefix) && !delegatePrefix.endsWith("/")) {
                delegatePrefix += "/";
            }
            this.prefix = delegatePrefix;
        }

        @Override
        public IResourceStream find(Class<?> clazz, String pathname) {
            // ClassPathResourceFinder can have a prefix so that looking for path "foo/bar" does not start at the classpath root, but rather in the
            // directory "/prefix/". If the delegate has such a prefix, we need to take it into account.
            String prefixedPath = prefix + pathname;
            if (knownPaths.mightContain(prefixedPath)) {
                log.debug("path '{}' probably exists, delegating", prefixedPath);
                // must pass non-prefixed path here because the delegate will also apply the prefix:
                return delegate.find(clazz, pathname);
            } else {
                log.debug("path '{}' does not exist", prefixedPath);
                return null;
            }
        }
    }
}
