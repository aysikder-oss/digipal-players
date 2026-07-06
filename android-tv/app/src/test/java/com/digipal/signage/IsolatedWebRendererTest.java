package com.digipal.signage;

    import static org.junit.Assert.assertEquals;

    import org.junit.Test;
    import org.junit.runner.RunWith;
    import org.robolectric.RobolectricTestRunner;

    @RunWith(RobolectricTestRunner.class)
    public class IsolatedWebRendererTest {
        @Test
        public void appendQueryParameterPreservesFragment() {
            String out = IsolatedWebRenderer.appendQueryParameter(
                    "https://www.digipalsignage.com/tv/render/design?id=1#preview",
                    "renderToken",
                    "123");

            assertEquals(
                    "https://www.digipalsignage.com/tv/render/design?id=1&renderToken=123#preview",
                    out);
        }

        @Test
        public void appendQueryParameterWorksWithoutQuery() {
            String out = IsolatedWebRenderer.appendQueryParameter(
                    "https://www.digipalsignage.com/tv/render/design#preview",
                    "renderToken",
                    "123");

            assertEquals(
                    "https://www.digipalsignage.com/tv/render/design?renderToken=123#preview",
                    out);
        }
    }
    