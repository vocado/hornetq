/*
 * Copyright 2010 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.core.protocol.core;

import org.hornetq.spi.core.protocol.RemotingConnection;


/**
 * Extension of RemotingConnection for the HornetQ core protocol
 *
 * @author Tim Fox
 *
 *
 */
public interface CoreRemotingConnection extends RemotingConnection
{  
   /**
    * return the channel with the channel id specified.
    * <p/>
    * If it does not exist create it with the confirmation window size.
    *
    * @param channelID      the channel id
    * @param confWindowSize the confirmation window size
    * @return the channel
    */
   Channel getChannel(long channelID, int confWindowSize);

   /**
    * add the channel with the specified channel id
    *
    * @param channelID the channel id
    * @param channel   the channel
    */
   void putChannel(long channelID, Channel channel);

   /**
    * remove the channel with the specified channel id
    *
    * @param channelID the channel id
    * @return true if removed
    */
   boolean removeChannel(long channelID);

   /**
    * generate a unique (within this connection) channel id
    *
    * @return the id
    */
   long generateChannelID();

   /**
    * resets the id generator used to when generating id's
    *
    * @param id the first id to set it to
    */
   void syncIDGeneratorSequence(long id);

   /**
    * return the next id that will be chosen.
    *
    * @return the id
    */
   long getIDGeneratorSequence();

   /**
    * return the current tomeout for blocking calls
    *
    * @return the timeout in milliseconds
    */
   long getBlockingCallTimeout();

   /**
    * return the transfer lock used when transferring connections.
    *
    * @return the lock
    */
   Object getTransferLock();
}