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
package io.fabric8.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.fabric8.api.AutoScaleStatus;
import io.fabric8.api.FabricRequirements;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;

/**
 * Helper methods for marshalling to and from JSON
 */
public final class RequirementsJson {

    private static final transient Logger LOG = LoggerFactory.getLogger(RequirementsJson.class);
    private static ObjectMapper mapper = new ObjectMapper();

    private RequirementsJson() {
        //Utility Class
    }
    static {
        mapper.getSerializationConfig().withSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public static ObjectMapper getMapper() {
        return mapper;
    }

    public static void writeRequirements(OutputStream out, FabricRequirements value) throws IOException {
    	writeRequirements(out,value,false);
    }

    public static void writeRequirements(OutputStream out, FabricRequirements value,boolean indent) throws IOException {
    	//ENTESB-5041 : fabric:requirements-export" command to indent requirements-export json output
    	if(indent){
    		mapper.enable(SerializationFeature.INDENT_OUTPUT);		
    	}else{
    		
    		 mapper.disable(SerializationFeature.INDENT_OUTPUT);
    	}
        mapper.writeValue(out, value);
    }


    public static String toJSON(FabricRequirements value) throws IOException {
        return valueToJSON(value);
    }

    public static String toJSON(AutoScaleStatus value) throws IOException {
        return valueToJSON(value);
    }

    protected static String valueToJSON(Object value) throws IOException {
        try {
            StringWriter writer = new StringWriter();
            mapper.writeValue(writer, value);

            return writer.toString();
        } catch (IOException e) {
            LOG.warn("Failed to marshal to JSON: " + e, e);
            throw new IOException(e.getMessage());
        }
    }

    public static FabricRequirements readRequirements(InputStream in) throws IOException {
        return mapper.readValue(in, FabricRequirements.class);
    }

    public static AutoScaleStatus readAutoScaleStatus(InputStream in) throws IOException {
        return mapper.readValue(in, AutoScaleStatus.class);
    }


    public static FabricRequirements fromJSON(String json) throws IOException {
        return valueFromJSON(json, FabricRequirements.class);
    }

    public static AutoScaleStatus autoScaleStatusFromJSON(String json) throws IOException {
        return valueFromJSON(json, AutoScaleStatus.class);
    }

    protected static <T> T valueFromJSON(String json, Class<T> clazz) throws IOException {
        if (json == null) {
            return null;
        }
        String trimmedJson = json.trim();
        if (trimmedJson.length() == 0 || trimmedJson.equals("{}")) {
            return null;
        }
        return mapper.reader(clazz).readValue(trimmedJson);
    }

    /**
     * Returns true if the objects are equal; by comparing their JSON marshalled strings
     */
    public static boolean equal(FabricRequirements a, FabricRequirements b) throws IOException {
        String json1 = toJSON(a);
        String json2 = toJSON(b);
        return Objects.equal(json1, json2);
    }
}
