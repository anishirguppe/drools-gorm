package org.drools.gorm.session;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.drools.KnowledgeBase;
import org.drools.RuleBase;
import org.drools.SessionConfiguration;
import org.drools.command.Command;
import org.drools.command.Context;
import org.drools.command.impl.ContextImpl;
import org.drools.command.impl.GenericCommand;
import org.drools.command.impl.KnowledgeCommandContext;
import org.drools.command.runtime.DisposeCommand;
import org.drools.common.EndOperationListener;
import org.drools.common.InternalKnowledgeRuntime;
import org.drools.gorm.GrailsIntegration;
import org.drools.gorm.session.marshalling.GormSessionMarshallingHelper;
import org.drools.impl.KnowledgeBaseImpl;
import org.drools.persistence.jpa.JpaJDKTimerService;
import org.drools.process.instance.WorkItemManager;
import org.drools.runtime.Environment;
import org.drools.runtime.KnowledgeSessionConfiguration;
import org.drools.runtime.StatefulKnowledgeSession;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.jdbc.Work;
import org.hibernate.metadata.ClassMetadata;
import org.hibernate.persister.entity.SingleTableEntityPersister;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class SingleSessionCommandService implements
        org.drools.command.SingleSessionCommandService {
    
    private final static Map<Class<?>, String> entitiesTablenames = new ConcurrentHashMap<Class<?>, String>();
    
    private SessionInfo sessionInfo;
    private GormSessionMarshallingHelper marshallingHelper;

    private StatefulKnowledgeSession ksession;
    private Environment env;
    private KnowledgeCommandContext kContext;
    private volatile boolean doRollback;

    public void checkEnvironment(Environment env) {
        configureEnvironment();
    }

    private void configureEnvironment() {
        env.set(HasBlob.GORM_UPDATE_SET, new CopyOnWriteArraySet<HasBlob<?>>());
    }
    
    public SingleSessionCommandService(RuleBase ruleBase,
                                       SessionConfiguration conf,
                                       Environment env) {
        this( new KnowledgeBaseImpl( ruleBase ),
              conf,
              env );
    }

    public SingleSessionCommandService(int sessionId,
                                       RuleBase ruleBase,
                                       SessionConfiguration conf,
                                       Environment env) {
        this( sessionId,
              new KnowledgeBaseImpl( ruleBase ),
              conf,
              env );
    }    
    
    public SingleSessionCommandService(KnowledgeBase kbase,
                                       KnowledgeSessionConfiguration conf,
                                       Environment env) {
        if ( conf == null ) {
            conf = new SessionConfiguration();
        }
        this.env = env;        
        
        checkEnvironment( this.env );        
        
        this.sessionInfo = GrailsIntegration.getGormDomainService().getNewSessionInfo(env);

        // create session but bypass command service
        this.ksession = kbase.newStatefulKnowledgeSession(conf, this.env);
        
        this.kContext = new KnowledgeCommandContext( new ContextImpl( "ksession",
                                                                      null ),
                                                     null,
                                                     null,
                                                     this.ksession,
                                                     null );

        ((JpaJDKTimerService) ((InternalKnowledgeRuntime) ksession).getTimerService()).setCommandService( this );
        
        this.marshallingHelper = new GormSessionMarshallingHelper( this.ksession,
                                                                  conf );
        this.sessionInfo.setMarshallingHelper( this.marshallingHelper );
        ((InternalKnowledgeRuntime) this.ksession).setEndOperationListener( new EndOperationListenerImpl() );        
        
        // Use the App scoped EntityManager if the user has provided it, and it is open.

        PlatformTransactionManager txManager = GrailsIntegration.getTransactionManager();
        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(DefaultTransactionDefinition.PROPAGATION_REQUIRED); 
        TransactionStatus status = txManager.getTransaction(txDef);
        try {
            registerRollbackSync();
            
            GrailsIntegration.getGormDomainService().saveDomain(this.sessionInfo);
            updateBlobs(false);
            txManager.commit(status);
        } catch ( Exception t1 ) {
            try {
                txManager.rollback(status);
            } catch ( Throwable t2 ) {
                throw new RuntimeException( "Could not commit session or rollback",
                                            t2 );
            }
            throw new RuntimeException( "Could not commit session",
                                        t1 );
        }

        // update the session id to be the same as the session info id
        ((InternalKnowledgeRuntime) ksession).setId( this.sessionInfo.getId() );

    }

    public SingleSessionCommandService(int sessionId,
                                       KnowledgeBase kbase,
                                       KnowledgeSessionConfiguration conf,
                                       Environment env) {
        if ( conf == null ) {
            conf = new SessionConfiguration();
        }
                

        this.env = env;
        
        checkEnvironment( this.env );
        
        initKsession( sessionId,
                      kbase,
                      conf );
    }

    public void initKsession(int sessionId,
                             KnowledgeBase kbase,
                             KnowledgeSessionConfiguration conf) {
        if ( !doRollback && this.ksession != null ) {
            return;
            // nothing to initialise
        }
        
        this.doRollback = false;       

        try {
            this.sessionInfo = GrailsIntegration.getGormDomainService().getSessionInfo(sessionId, env);
        } catch ( Exception e ) {
            throw new RuntimeException( "Could not find session data for id " + sessionId,
                                        e );
        }

        if ( sessionInfo == null ) {
            throw new RuntimeException( "Could not find session data for id " + sessionId );
        }

        if ( this.marshallingHelper == null ) {
            // this should only happen when this class is first constructed
            this.marshallingHelper = new GormSessionMarshallingHelper( kbase,
                                                                      conf,
                                                                      env );
        }

        this.sessionInfo.setMarshallingHelper( this.marshallingHelper );

        // if this.ksession is null, it'll create a new one, else it'll use the existing one
        this.ksession = this.marshallingHelper.loadSnapshot( this.sessionInfo.getData(),
                                                             this.ksession );

        // update the session id to be the same as the session info id
        ((InternalKnowledgeRuntime) ksession).setId( this.sessionInfo.getId() );

        ((InternalKnowledgeRuntime) this.ksession).setEndOperationListener( new EndOperationListenerImpl() );

        ((JpaJDKTimerService) ((InternalKnowledgeRuntime) ksession).getTimerService()).setCommandService( this );
        
        if ( this.kContext == null ) {
            // this should only happen when this class is first constructed
            this.kContext = new KnowledgeCommandContext( new ContextImpl( "ksession",
                        null ),
                    null,
                    null,
                    this.ksession,
                    null );
        }

    }


    public class EndOperationListenerImpl implements EndOperationListener {

        public void endOperation(InternalKnowledgeRuntime kruntime) {
            SingleSessionCommandService.this.sessionInfo.setLastModificationDate( new Date( kruntime.getLastIdleTimestamp() ) );
        }
    }

    public Context getContext() {
        return this.kContext;
    }

    public synchronized <T> T execute(Command<T> command) {
        PlatformTransactionManager txManager = GrailsIntegration.getTransactionManager();
        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(DefaultTransactionDefinition.PROPAGATION_REQUIRED); 
        TransactionStatus status = txManager.getTransaction(txDef);

        try {
            initKsession( this.sessionInfo.getId(),
                          this.marshallingHelper.getKbase(),
                          this.marshallingHelper.getConf() );
            
            registerRollbackSync();
            configureEnvironment();

            T result = ((GenericCommand<T>) command).execute( this.kContext );
        
            updateBlobs(command instanceof DisposeCommand);
            txManager.commit(status);

            return result;
        } catch (RuntimeException e){
            status.setRollbackOnly();
            throw e;
        } catch ( Exception e ) {
            status.setRollbackOnly();
            throw new RuntimeException("Wrapped exception see cause", e);
        }
    }

    private void updateBlobs(final boolean isDispose) {
        final Set<HasBlob<?>> updates = (Set<HasBlob<?>>) env.get(HasBlob.GORM_UPDATE_SET);
        configureEnvironment();
        Session session = GrailsIntegration.getCurrentSession();
        
        session.doWork(new Work() {
            @Override
            public void execute(Connection conn) throws SQLException {
                boolean hasStoredKSession = false; 
                for (final HasBlob<?> hasBlob : updates) {
                    if (!hasBlob.isDeleted()) {
                        persistBlob(isDispose, conn, hasBlob);
                    }
                    hasStoredKSession |= SingleSessionCommandService.this.sessionInfo == hasBlob;
                }
                if (!hasStoredKSession) {
                    persistBlob(isDispose, conn, SingleSessionCommandService.this.sessionInfo);
                }
            }

            private void persistBlob(boolean isDispose, Connection conn,
                    HasBlob<?> hasBlob) throws SQLException {
                byte[] blob = null;
                try {
                    blob = hasBlob.generateBlob();
                } catch (RuntimeException e) {
                    if (!isDispose) {
                        throw e;
                    }
                }
                if (blob != null && blob.length > 0) {
                    PreparedStatement ps = conn.prepareStatement("update "
                            + getTablename(hasBlob) 
                            + " set data = ? where id = ?");
                    try {
                        int i = 1;
                        ps.setBinaryStream(i++, new ByteArrayInputStream(blob), blob.length);
                        ps.setLong(i++, hasBlob.getId().longValue());
                        int count = ps.executeUpdate();
                        if (count != 1) {
                            throw new IllegalStateException("update blob for id:  " + hasBlob 
                                    + " has failed, count: " + count);
                        }
                    } finally {
                        ps.close();
                    }
                }
            }
        });
    }

    public void dispose() {
        if ( ksession != null ) {
            ksession.dispose();
        }
    }

    public int getSessionId() {
        return sessionInfo.getId();
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> getSyncronizationMap() {
        Map<Object, Object> map = (Map<Object, Object>) env.get("synchronizations");
        if ( map == null ) {
            map = Collections.synchronizedMap( new IdentityHashMap<Object, Object>() );
            env.set("synchronizations", map);
        }
        return map;
    }
    
    private void registerRollbackSync() throws IllegalStateException {
        Map<Object, Object> map = getSyncronizationMap();

        if (!map.containsKey( this )) {
            TransactionSynchronizationManager.registerSynchronization(new SynchronizationImpl());
            map.put(this, this);
        }
    }

    private class SynchronizationImpl
        implements TransactionSynchronization {

        @Override
        public void suspend() {}

        @Override
        public void resume() {}

        @Override
        public void flush() {}

        @Override
        public void beforeCommit(boolean readOnly) {}

        @Override
        public void beforeCompletion() {}

        @Override
        public void afterCommit() {}

        @Override
        public void afterCompletion(int status) {
            try {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    SingleSessionCommandService.this.rollback();
                }
                // clean up cached process and work item instances
                StatefulKnowledgeSession ksession = SingleSessionCommandService.this.ksession;
                if (ksession != null) {
                    ((InternalKnowledgeRuntime) ksession).getProcessRuntime().clearProcessInstances();
                    ((WorkItemManager) ksession.getWorkItemManager()).clear();
                }
            } finally {
                SingleSessionCommandService.this.getSyncronizationMap().remove(SingleSessionCommandService.this);
            }
        }
    }

    private void rollback() {
        this.doRollback = true;
    }
    
    private String getTablename(HasBlob<?> hasBlob) {
        String tablename = entitiesTablenames.get(hasBlob.getClass());
        if (tablename == null) {
            SessionFactory sf = GrailsIntegration.getCurrentSessionFactory();
            ClassMetadata classMetadata = sf.getClassMetadata(hasBlob.getClass());
            if (classMetadata instanceof SingleTableEntityPersister) {
                SingleTableEntityPersister step = (SingleTableEntityPersister) classMetadata;
                tablename = step.getTableName();
                entitiesTablenames.put(hasBlob.getClass(), tablename);
            }
        }
        return tablename;
    
    }
}
