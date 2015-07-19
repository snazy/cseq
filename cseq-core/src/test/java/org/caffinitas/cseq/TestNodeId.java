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

import java.nio.ByteBuffer;

import org.caffinitas.cseq.nodeid.NodeId;

/**
 * TODO add javadoc explaining the purpose of this class/interface/annotation
 */
public class TestNodeId extends NodeId
{
    private final int id;

    public TestNodeId(int id)
    {
        this.id = id;
    }

    public ByteBuffer nodeId()
    {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.putInt(id);
        bb.flip();
        return bb;
    }
}
