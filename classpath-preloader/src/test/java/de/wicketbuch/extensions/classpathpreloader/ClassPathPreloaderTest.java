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

import org.apache.wicket.Application;
import org.apache.wicket.core.util.resource.ClassPathResourceFinder;
import org.apache.wicket.util.resource.IResourceStream;
import org.apache.wicket.util.tester.WicketTester;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class ClassPathPreloaderTest {

    private WicketTester tester;
    private ClassPathPreloader underTest;

    @Before
    public void setUp() throws Exception {
        tester = new WicketTester();
        underTest = ClassPathPreloader.configure(tester.getApplication());
    }

    @After
    public void tearDown() throws Exception {
        tester.destroy();
        tester = null;
    }

    @Test
    public void findsTestClassWithoutPrefix() {
        final ClassPathPreloader.PrescanningClasspathResourceFinder finder = underTest.new PrescanningClasspathResourceFinder(
                new ClassPathResourceFinder(""));
        assertNotNull(finder.find(Application.class, "de/wicketbuch/extensions/classpathpreloader/ClassPathPreloaderTest.class"));
    }

    @Test
    public void doesNotAttemptToLoadNonexistentFile() {
        final ClassPathPreloader.PrescanningClasspathResourceFinder finder = underTest.new PrescanningClasspathResourceFinder(new ClassPathResourceFinder("") {
            @Override
            public IResourceStream find(Class<?> clazz, String pathname) {
                throw new AssertionError("should not have called this");
            }
        });
        finder.find(Application.class, "i/dont/exist.properties");
    }

    @Test
    public void findsPropertiesInDependencyJar() {
        final ClassPathPreloader.PrescanningClasspathResourceFinder finder = underTest.new PrescanningClasspathResourceFinder(
                new ClassPathResourceFinder(""));
        assertNotNull(finder.find(Application.class, "org/apache/wicket/Application.properties"));
    }

    @Test
    public void findsPropertiesWithPrefixResourceFinder() {
        final ClassPathPreloader.PrescanningClasspathResourceFinder finder = underTest.new PrescanningClasspathResourceFinder(
                new ClassPathResourceFinder("subfolder"));
        assertNotNull(finder.find(Application.class, "test.properties"));
    }

    @Test
    public void findsPropertiesWithPrefixInDependencyJar() {
        final ClassPathPreloader.PrescanningClasspathResourceFinder finder = underTest.new PrescanningClasspathResourceFinder(
                new ClassPathResourceFinder("META-INF"));
        assertNotNull(finder.find(Application.class, "services/org.apache.wicket.IInitializer"));
    }
}
