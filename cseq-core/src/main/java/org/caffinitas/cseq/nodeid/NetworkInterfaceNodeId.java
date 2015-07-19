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
package org.caffinitas.cseq.nodeid;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Enumeration;

/**
 * TODO add javadoc explaining the purpose of this class/interface/annotation
 */
public class NetworkInterfaceNodeId extends NodeId
{
    private final byte[] hardwareAddress;
    private transient String s;
    private transient final int h;

    public static NetworkInterfaceNodeId byName(String name) throws SocketException
    {
        return new NetworkInterfaceNodeId(NetworkInterface.getByName(name));
    }

    public static NetworkInterfaceNodeId any() throws SocketException
    {
        for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
             e.hasMoreElements();)
        {
            NetworkInterface netIf = e.nextElement();
            if (netIf.getHardwareAddress() == null || netIf.getHardwareAddress().length == 0)
                continue;
            return new NetworkInterfaceNodeId(netIf);
        }
        throw new IllegalStateException("No suitable network interface");
    }

    public NetworkInterfaceNodeId(NetworkInterface netIf) throws SocketException
    {
        if (netIf == null)
            throw new NullPointerException("Network interface is null");
        this.hardwareAddress = netIf.getHardwareAddress();
        if (this.hardwareAddress == null || this.hardwareAddress.length == 0)
            throw new IllegalArgumentException("Hardware address of network interface " + netIf + " is null or empty");
        h = Arrays.hashCode(hardwareAddress);
    }

    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NetworkInterfaceNodeId that = (NetworkInterfaceNodeId) o;

        return Arrays.equals(hardwareAddress, that.hardwareAddress);
    }

    public int hashCode()
    {
        return h;
    }

    public ByteBuffer nodeId()
    {
        return ByteBuffer.wrap(hardwareAddress);
    }

    public String toString()
    {
        if (s == null)
        {
            StringBuilder sb = new StringBuilder();
            for (byte b : hardwareAddress)
            {
                if (sb.length() > 0)
                    sb.append(':');
                if (b >= 0 && b <= 0xf)
                    sb.append('0');
                sb.append(Integer.toHexString(b & 0xff));
            }
            s = sb.toString();
        }
        return s;
    }
}
