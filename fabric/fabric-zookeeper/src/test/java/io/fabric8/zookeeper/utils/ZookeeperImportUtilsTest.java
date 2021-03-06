/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.zookeeper.utils;

import java.io.StringReader;
import java.net.URL;
import java.util.List;
import java.util.Properties;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;

public class ZookeeperImportUtilsTest extends ZookeeperServerTestSupport {

    @Test
    public void testNoExceptionFromImportProperties() throws Exception {
        URL url = this.getClass().getResource("/import-test.properties");
        ZookeeperImportUtils.importFromPropertiesFile(null, url.toString(), "mypid", null, null, true);
    }

    @Test
    public void testNoExceptionFromImportFromFileSystem() throws Exception {
        String target = "/fabric/profiles/mq-base.profile/import-test.properties";
        String source = this.getClass().getResource("/import-test.properties").getFile();

        ZookeeperImportUtils.importFromFileSystem(curator, source, target, null, null, false, false, false);
    }

    @Test
    public void testImportDirectoryData() throws Exception {
        String target = "/fabric1/";
        String source = this.getClass().getResource("/import1").getFile();

        ZookeeperImportUtils.importFromFileSystem(curator, source, target, null, null, false, false, false);
        assertThat(curator.getChildren().forPath("/fabric1/directory").size(), equalTo(0));
        assertThat(new String(curator.getData().forPath("/fabric1/directory")), endsWith("property1=value1\n"));
    }

    @Test
    public void testImportFileData() throws Exception {
        String target = "/fabric2/";
        String source = this.getClass().getResource("/import2").getFile();

        ZookeeperImportUtils.importFromFileSystem(curator, source, target, null, null, false, false, false);
        assertThat(curator.getChildren().forPath("/fabric2").size(), equalTo(1));
        assertThat(curator.checkExists().forPath("/fabric2/directory"), nullValue());
        assertThat(new String(curator.getData().forPath("/fabric2/directory.cfgx")), endsWith("property1=value1\n"));
    }

    @Test
    public void testImportOldProfileData() throws Exception {
        String target = "/fabric3/";
        String source = this.getClass().getResource("/import3").getFile();

        ZookeeperImportUtils.importFromFileSystem(curator, source, target, null, null, false, false, false);
        assertThat(curator.getChildren().forPath("/fabric3/fabric/configs/versions/1.0/profiles/p1").size(), equalTo(1));
        Properties properties = new Properties();
        properties.load(new StringReader(new String(curator.getData().forPath("/fabric3/fabric/configs/versions/1.0/profiles/p1/io.fabric8.agent.properties"))));
        assertThat(properties.getProperty("parents"), equalTo("x y z"));


        // now lets test the facade
        ZooKeeperFacade facade = new ZooKeeperFacade(curator);
        List<String> childTextData = facade.matchingDescendantStringData("/fabric3/fabric/configs/versions/*/profiles/*");
        assertThat("children size: " + childTextData, childTextData.size(), equalTo(1));
        String firstText = childTextData.get(0).trim();
        assertThat(firstText, containsString("x y z"));
    }

    @Test
    public void testImportServletRegistration() throws Exception {
        String target = "/fabric4/";
        String source = this.getClass().getResource("/import4").getFile();

        ZookeeperImportUtils.importFromFileSystem(curator, source, target, null, null, false, false, false);
        assertThat(curator.getChildren().forPath("/fabric4/fabric/registry/clusters/servlets/io.fabric8.fabric-redirect/1.0.0/*").size(), equalTo(1));
        assertThat(curator.getChildren().forPath("/fabric4/fabric/registry/clusters/servlets/io.fabric8.fabric-redirect/1.0.0/*").get(0), equalTo("root"));

        // now lets test the facade
        ZooKeeperFacade facade = new ZooKeeperFacade(curator);
        List<String> children = facade.matchingDescendants("/fabric4/fabric/registry/clusters/servlets/*/1.0.0");
        assertThat("children size: " + children, children.size(), not(equalTo(0)));
    }

}
