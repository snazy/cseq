/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.caffinitas.cseq;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * <p>Annotation for a Class or Method that defines a Cassandra Version requirement.  If the cassandra verison in used
 * does not meet the version requirement, the test is skipped.</p>
 *
 * @see {@link TestListener#beforeInvocation(org.testng.IInvokedMethod, org.testng.ITestResult)} for usage.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface CassandraVersion
{
    /**
     * @return The major version required to execute this test, i.e. "2.0"
     */
    public double major();

    /**
     * @return The minor version required to execute this test, i.e. "0"
     */
    public int minor() default 0;

    /**
     * @return The description returned if this version requirement is not met.
     */
    public String description() default "Does not meet minimum version requirement.";
}
