/*
 *      Copyright (C) 2014 Robert Stupp, Koeln, Germany, robert-stupp.de
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

import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import org.caffinitas.cseq.nodeid.NetworkInterfaceNodeId;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * TODO add javadoc explaining the purpose of this class/interface/annotation
 */
public class NetworkInterfaceNodeIdTest
{
    @Test
    public void testNetworkInterfaceNodeId() throws Exception
    {
        Set<NetworkInterfaceNodeId> nodeIds = new HashSet<>();
        int count = 0;
        for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
             e.hasMoreElements();)
        {
            NetworkInterface netIf = e.nextElement();
            if (netIf.getHardwareAddress() == null || netIf.getHardwareAddress().length == 0)
                continue;

            NetworkInterfaceNodeId nodeId = new NetworkInterfaceNodeId(netIf);
            nodeIds.add(nodeId);
            count++;

            assertEquals(nodeIds.size(), count);
        }
    }
}
