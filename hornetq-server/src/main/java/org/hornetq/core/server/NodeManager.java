/*
 * Copyright 2009 Red Hat, Inc.
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package org.hornetq.core.server;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.hornetq.api.core.HornetQIllegalStateException;
import org.hornetq.api.core.SimpleString;
import org.hornetq.utils.UUID;
import org.hornetq.utils.UUIDGenerator;

/**
 * @author <a href="mailto:andy.taylor@jboss.com">Andy Taylor</a>
 *         Date: Oct 13, 2010
 *         Time: 2:38:40 PM
 */
public abstract class NodeManager implements HornetQComponent
{
   protected static final byte FIRST_TIME_START = '0';
   private static final String SERVER_LOCK_NAME = "server.lock";
   private static final String ACCESS_MODE = "rw";

   protected final boolean replicatedBackup;
   private final String directory;
   private final Object nodeIDGuard = new String();
   private SimpleString nodeID;
   private UUID uuid;
   private String nodeGroupName;
   private boolean isStarted = false;

   protected FileChannel channel;

   public NodeManager(final boolean replicatedBackup, final String directory)
   {
      this.directory = directory;
      this.replicatedBackup = replicatedBackup;
   }

   // --------------------------------------------------------------------

   public abstract void awaitLiveNode() throws Exception;

   public abstract void startBackup() throws Exception;

   public abstract void startLiveNode() throws Exception;

   public abstract void pauseLiveServer() throws Exception;

   public abstract void crashLiveServer() throws Exception;

   public abstract void releaseBackup() throws Exception;

   // --------------------------------------------------------------------

   public synchronized void start() throws Exception
   {
      isStarted = true;
   }

   public boolean isStarted()
   {
      return isStarted;
   }

   public SimpleString getNodeId()
   {
      synchronized (nodeIDGuard)
      {
         return nodeID;
      }
   }

   public abstract SimpleString readNodeId() throws HornetQIllegalStateException, IOException;

   public UUID getUUID()
   {
      synchronized (nodeIDGuard)
      {
         return uuid;
      }
   }

   /**
    * Sets the nodeID.
    * <p>
    * Only used by replicating backups.
    * @param nodeID
    */
   public void setNodeID(String nodeID)
   {
      synchronized (nodeIDGuard)
      {
         this.nodeID = new SimpleString(nodeID);
         this.uuid = new UUID(UUID.TYPE_TIME_BASED, UUID.stringToBytes(nodeID));
      }
   }

   /**
    * @param generateUUID
    */
   protected void setUUID(UUID generateUUID)
   {
      synchronized (nodeIDGuard)
      {
         uuid = generateUUID;
         nodeID = new SimpleString(uuid.toString());
      }
   }

   public void setNodeGroupName(String nodeGroupName)
   {
      this.nodeGroupName = nodeGroupName;
   }

   public String getNodeGroupName()
   {
      return nodeGroupName;
   }

   public abstract boolean isAwaitingFailback() throws Exception;

   public abstract boolean isBackupLive() throws Exception;

   public abstract void interrupt();

   @Override
   public synchronized void stop() throws Exception
   {
      FileChannel channelCopy = channel;
      if (channelCopy != null)
         channelCopy.close();
      isStarted = false;
   }

   public final void stopBackup() throws Exception
   {
      if (replicatedBackup && getNodeId() != null)
      {
         setUpServerLockFile();
      }
      releaseBackup();
   }

   /**
    * Ensures existence of persistent information about the server's nodeID.
    * <p>
    * Roughly the different use cases are:
    * <ol>
    * <li>old live server restarts: a server.lock file already exists and contains a nodeID.
    * <li>new live server starting for the first time: no file exists, and we just *create* a new
    * UUID to use as nodeID
    * <li>replicated backup received its nodeID from its live: no file exists, we need to persist
    * the *current* nodeID
    * </ol>
    * @throws Exception
    * @throws FileNotFoundException
    * @throws IOException
    */
   protected synchronized final void setUpServerLockFile() throws FileNotFoundException, IOException
   {
      File serverLockFile = newFile(SERVER_LOCK_NAME);

      boolean fileCreated = false;

      if (!serverLockFile.exists())
      {
         try
         {
            fileCreated = serverLockFile.createNewFile();
         }
         catch (RuntimeException e)
         {
            HornetQServerLogger.LOGGER.nodeManagerCantOpenFile(e, serverLockFile);
            throw e;
         }
         catch (IOException e)
         {
            HornetQServerLogger.LOGGER.nodeManagerCantOpenFile(e, serverLockFile);
            throw e;
         }
         if (!fileCreated)
         {
            throw new IllegalStateException("Unable to create server lock file");
         }
      }

      @SuppressWarnings("resource")
      RandomAccessFile raFile = new RandomAccessFile(serverLockFile, ACCESS_MODE);

      channel = raFile.getChannel();

      if (fileCreated)
      {
         ByteBuffer id = ByteBuffer.allocateDirect(3);
         byte[] bytes = new byte[3];
         bytes[0] = FIRST_TIME_START;
         bytes[1] = FIRST_TIME_START;
         bytes[2] = FIRST_TIME_START;
         id.put(bytes, 0, 3);
         id.position(0);
         channel.write(id, 0);
         channel.force(true);
      }

      createNodeId();
   }

   /**
    * @return
    */
   protected final File newFile(final String fileName)
   {
      File file = new File(directory, fileName);
      return file;
   }

   protected final synchronized void createNodeId() throws IOException
   {
      synchronized (nodeIDGuard)
      {
         ByteBuffer id = ByteBuffer.allocateDirect(16);
         int read = channel.read(id, 3);
         if (replicatedBackup)
         {
            id.position(0);
            id.put(getUUID().asBytes(), 0, 16);
            id.position(0);
            channel.write(id, 3);
            channel.force(true);
         }
         else if (read != 16)
         {
            setUUID(UUIDGenerator.getInstance().generateUUID());
            id.put(getUUID().asBytes(), 0, 16);
            id.position(0);
            channel.write(id, 3);
            channel.force(true);
         }
         else
         {
            byte[] bytes = new byte[16];
            id.position(0);
            id.get(bytes);
            setUUID(new UUID(UUID.TYPE_TIME_BASED, bytes));
         }
      }
   }

}
