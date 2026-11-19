// Copyright 2026 JanusGraph Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.janusgraph.lgj;

import com.adaworldapi.lancegraph.FacetId;

/**
 * The filters a lowered traversal applies to its last frontier: a label class match and/or a
 * property equality, each optional (a null facet means that filter is absent).
 *
 * @param labelFacet facet whose class id is the vertex label, or null
 * @param labelClassid the class id the label maps to
 * @param propertyFacet facet whose low 64 payload bits hold the property, or null
 * @param propertyValue the value those 64 bits must equal
 */
record HopFilter(FacetId labelFacet, int labelClassid, FacetId propertyFacet, long propertyValue) {

    @Override
    public String toString() {
        return (labelFacet == null ? "" : ", label=" + labelFacet + ":" + labelClassid)
                + (propertyFacet == null ? "" : ", property=" + propertyFacet + "==" + propertyValue);
    }
}
