package org.exoplatform.social.addons.updater;

import java.util.Collection;
import java.util.Iterator;

import javax.jcr.Node;
import javax.jcr.NodeIterator;

import org.exoplatform.commons.api.event.EventManager;
import org.exoplatform.commons.persistence.impl.EntityManagerService;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.management.annotations.Managed;
import org.exoplatform.management.annotations.ManagedDescription;
import org.exoplatform.management.jmx.annotations.NameTemplate;
import org.exoplatform.management.jmx.annotations.Property;
import org.exoplatform.social.addons.storage.dao.ProfileItemDAO;
import org.exoplatform.social.addons.storage.dao.ConnectionDAO;
import org.exoplatform.social.addons.storage.entity.Connection;
import org.exoplatform.social.core.chromattic.entity.IdentityEntity;
import org.exoplatform.social.core.chromattic.entity.RelationshipEntity;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.relationship.model.Relationship;
import org.exoplatform.social.core.storage.api.IdentityStorage;

@Managed
@ManagedDescription("Social migration relationships from JCR to MYSQl service.")
@NameTemplate({@Property(key = "service", value = "social"), @Property(key = "view", value = "migration-relationships") })
public class RelationshipMigrationService extends AbstractMigrationService<Relationship> {
  public static final String EVENT_LISTENER_KEY = "SOC_RELATIONSHIP_MIGRATION";
  private final ConnectionDAO connectionDAO;
  private static final int LIMIT_REMOVED_THRESHOLD = 10;
  private final ProfileItemDAO profileItemDAO;

  public RelationshipMigrationService(InitParams initParams,
                                      IdentityStorage identityStorage,
                                      ConnectionDAO connectionDAO,
                                      ProfileItemDAO profileItemDAO,
                                      ProfileMigrationService profileMigration,
                                      EventManager<Relationship, String> eventManager,
                                      EntityManagerService entityManagerService) {

    super(initParams, identityStorage, eventManager, entityManagerService);
    this.connectionDAO = connectionDAO;
    this.profileItemDAO = profileItemDAO;
    this.LIMIT_THRESHOLD = getInteger(initParams, LIMIT_THRESHOLD_KEY, 200);
  }

  @Override
  protected void beforeMigration() throws Exception {
    MigrationContext.setConnectionDone(false);
  }

  @Override
  @Managed
  @ManagedDescription("Manual to start run migration data of relationships from JCR to MYSQL.")
  public void doMigration() throws Exception {
      boolean begunTx = startTx();
      long offset = 0;
      int total = 0;

      long t = System.currentTimeMillis();
      try {
        if (connectionDAO.count() > 0) {
          MigrationContext.setConnectionDone(true);
          return;
        }
        
        LOG.info("| \\ START::Relationships migration ---------------------------------");
        NodeIterator nodeIter  = getIdentityNodes(offset, LIMIT_THRESHOLD);
        
        int relationshipNo = 0;
        Node identityNode = null;
        while (nodeIter.hasNext()) {
          if(forkStop) {
            break;
          }
          relationshipNo = 0;
          identityNode = nodeIter.nextNode();
          LOG.info(String.format("|  \\ START::user number: %s (%s user)", offset, identityNode.getName()));
          long t1 = System.currentTimeMillis();
          
          IdentityEntity identityEntity = _findById(IdentityEntity.class, identityNode.getUUID());
          Identity identityFrom = new Identity(OrganizationIdentityProvider.NAME, identityEntity.getRemoteId());
          identityFrom.setId(identityEntity.getId());
          //
          Iterator<RelationshipEntity> it = identityEntity.getRelationship().getRelationships().values().iterator();
          relationshipNo += migrateRelationshipEntity(it, identityFrom, false, Relationship.Type.CONFIRMED);
          //
          it = identityEntity.getSender().getRelationships().values().iterator();
          relationshipNo += migrateRelationshipEntity(it, identityFrom, false, Relationship.Type.OUTGOING);
          //
          it = identityEntity.getReceiver().getRelationships().values().iterator();
          relationshipNo += migrateRelationshipEntity(it, identityFrom, true, Relationship.Type.INCOMING);
          //
          offset++;
          total += relationshipNo;
          if (offset % LIMIT_THRESHOLD == 0) {
            endTx(begunTx);
            RequestLifeCycle.end();
            RequestLifeCycle.begin(PortalContainer.getInstance());
            begunTx = startTx();
            nodeIter  = getIdentityNodes(offset, LIMIT_THRESHOLD);
          }
          
          LOG.info(String.format("|  / END::user number %s (%s user) with %s relationship(s) user consumed %s(ms)", relationshipNo, identityNode.getName(), relationshipNo, System.currentTimeMillis() - t1));
        }
        
      } finally {
        endTx(begunTx);
        RequestLifeCycle.end();
        RequestLifeCycle.begin(PortalContainer.getInstance());
        LOG.info(String.format("| / END::Relationships migration for (%s) user(s) with %s relationship(s) consumed %s(ms)", offset, total, System.currentTimeMillis() - t));
      }
  }
  
  private int migrateRelationshipEntity(Iterator<RelationshipEntity> it, Identity owner, boolean isIncoming, Relationship.Type status) {
    int doneConnectionNo = 0;
    startTx();
    while (it.hasNext()) {
      RelationshipEntity relationshipEntity = it.next();
      String receiverId = relationshipEntity.getTo().getId().equals(owner.getId()) ? relationshipEntity.getFrom().getId() : relationshipEntity.getTo().getId();
      Identity receiver = identityStorage.findIdentityById(receiverId);
      //
      Connection entity = new Connection();
      entity.setSenderId(isIncoming ? receiver.getId() : owner.getId());
      entity.setReceiverId(isIncoming ? owner.getId() : receiver.getId());
      entity.setStatus(status);
      entity.setReceiver(profileItemDAO.findProfileItemByIdentityId(isIncoming ? owner.getId() : receiver.getId()));
      //

      connectionDAO.create(entity);
      ++doneConnectionNo;
      if(doneConnectionNo % LIMIT_THRESHOLD == 0) {
        endTx(true);
        entityManagerService.endRequest(PortalContainer.getInstance());
        entityManagerService.startRequest(PortalContainer.getInstance());
        startTx();
      }
    }
    return doneConnectionNo;
  }

  @Override
  protected void afterMigration() throws Exception {
    if(forkStop) {
      return;
    }
    MigrationContext.setConnectionDone(true);
  }

  public void doRemove() throws Exception {
    LOG.info("| \\ START::cleanup Relationships ---------------------------------");
    long t = System.currentTimeMillis();
    long timePerUser = System.currentTimeMillis();
    RequestLifeCycle.begin(PortalContainer.getInstance());
    int offset = 0;
    NodeIterator nodeIter  = getIdentityNodes(offset, LIMIT_THRESHOLD);
    Node node = null;
    
    while (nodeIter.hasNext()) {
      node = nodeIter.nextNode();
      LOG.info(String.format("|  \\ START::cleanup Relationship of user number: %s (%s user)", offset, node.getName()));
      IdentityEntity identityEntity = _findById(IdentityEntity.class, node.getUUID());
      offset++;
      
      Collection<RelationshipEntity> entities = identityEntity.getRelationship().getRelationships().values();
      removeRelationshipEntity(entities);
      // 
      entities = identityEntity.getSender().getRelationships().values();
      removeRelationshipEntity(entities);
      //
      entities = identityEntity.getReceiver().getRelationships().values();
      removeRelationshipEntity(entities);
      
      LOG.info(String.format("|  / END::cleanup (%s user) consumed time %s(ms)", node.getName(), System.currentTimeMillis() - timePerUser));
      
      timePerUser = System.currentTimeMillis();
      if(offset % LIMIT_THRESHOLD == 0) {
        RequestLifeCycle.end();
        RequestLifeCycle.begin(PortalContainer.getInstance());
        nodeIter = getIdentityNodes(offset, LIMIT_THRESHOLD);
      }
    }
    //
    RequestLifeCycle.end();
    LOG.info(String.format("| / END::cleanup Relationships migration for (%s) user consumed %s(ms)", offset, System.currentTimeMillis() - t));
  }
  
  
  private void removeRelationshipEntity(Collection<RelationshipEntity> entities) {
    try {
      int offset = 0;
      Iterator<RelationshipEntity> it = entities.iterator();
      while (it.hasNext()) {
        RelationshipEntity relationshipEntity = it.next();
        getSession().remove(relationshipEntity);
        ++offset;
        if (offset % LIMIT_REMOVED_THRESHOLD == 0) {
          getSession().save();
        }
      }
    } finally {
      getSession().save();
    }
  }

  

  @Override
  @Managed
  @ManagedDescription("Manual to stop run miguration data of relationships from JCR to MYSQL.")
  public void stop() {
    super.stop();
  }

  protected String getListenerKey() {
    return EVENT_LISTENER_KEY;
  }
}
