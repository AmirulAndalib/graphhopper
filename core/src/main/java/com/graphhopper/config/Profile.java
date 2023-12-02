/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PMap;

/**
 * Corresponds to an entry of the `profiles` section in `config.yml` and specifies the properties of a routing profile.
 * The name used here needs to be used when setting up CH/LM preparations. See also the documentation in
 * `config-example.yml'
 *
 * @see CHProfile
 * @see LMProfile
 */
public class Profile {
    private String name = null;
    private String weighting = "custom";
    private CustomModel customModel = new CustomModel();
    private PMap hints = new PMap();

    public static void validateProfileName(String profileName) {
        if (!profileName.matches("^[a-z0-9_\\-]+$")) {
            throw new IllegalArgumentException("Profile names may only contain lower case letters, numbers and underscores, given: " + profileName);
        }
    }

    private Profile() {
        // default constructor needed for jackson
    }

    public Profile(String name) {
        setName(name);
    }

    public Profile(Profile p) {
        setName(p.getName());
        setWeighting(p.getWeighting());
        setCustomModel(p.getCustomModel());
        hints = new PMap(p.getHints());
    }

    public String getName() {
        return name;
    }

    public Profile setName(String name) {
        validateProfileName(name);
        this.name = name;
        return this;
    }

    public String getWeighting() {
        return weighting;
    }

    public Profile setWeighting(String weighting) {
        this.weighting = weighting;
        return this;
    }

    public Profile setCustomModel(CustomModel customModel) {
        if (customModel != null)
            customModel.internal();
        this.customModel = customModel;
        return this;
    }

    public CustomModel getCustomModel() {
        return customModel;
    }

    @JsonIgnore
    public PMap getHints() {
        return hints;
    }

    @JsonAnySetter
    public Profile putHint(String key, Object value) {
        this.hints.putObject(key, value);
        return this;
    }

    @Override
    public String toString() {
        return createContentString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Profile profile = (Profile) o;
        return name.equals(profile.name);
    }

    private String createContentString() {
        // used to check against stored custom models, see #2026
        return "name=" + name + "|weighting=" + weighting + "|custom_model=" + getCustomModel() + "|hints=" + hints;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    public int getVersion() {
        return Helper.staticHashCode(createContentString());
    }
}
