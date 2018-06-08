**Current version**: unreleased

# Classpath prescanning to accelerate Wicket resource loading

Wicket loads its resources (HTML files, properties files, etc) lazily, i.e. on first access. To allow for locale and style overrides, it tries
more specific paths first (e.g. FooPage_de_green.html) before trying more generic ones (FooPage.html). This works very well in practice, except
on a few systems where classpath/filesystem access is unusually expensive. The symptom of this is that the first request with a locale or style
will take tens of seconds longer than expected. Subsequent requests using the same locale or style will not have this problem, since the relevant
files will be cached. Some IBM systems seem to exhibit this issue.

If this affects your application, you can trade some memory and some time in application startup for faster first request times. 
`ClassPathPreloader` will scan the entire classpath during application startup so that only existing files will actually be accessed. It does this
by wrapping Wicket's `ClassPathResourceFinder`s and not letting them proceed with paths known to be invalid.

It uses a constant amount of memory (roughly 600KB), regardless of classpath size, due to using a Bloom Filter with a fixed estimate of 1,000,000 
entries. See the javadoc of `ClassPathPreloader` for more information.

# How to use

Do this in your `Application.init`:

    ClassPathPreloader.configure(this);

If you have any custom `IResourceFinder`s, initialize them before this.

**Don't** use this unless your production system is measurably affected by the symptom described above. To keep development turnaround times low,
use it only in `DEPLOYMENT` mode. 
  
## Maven coordinates

    <dependency>
        <groupId>de.wicketbuch.extensions</groupId>
        <artifactId>classpath-preloader</artifactId>
        <version>not yet released</version>
    </dependency>

Make sure you choose the correct version for the version of Wicket you are
using, they are suffixed with `.wicket7` and `.wicket8` respectively.

This project uses [Semantic Versioning](http://semver.org/), so you can rely on
things not breaking within a major version.
