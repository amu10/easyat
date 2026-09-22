package io.github.easyat.storage.file;
import io.github.easyat.core.*; import java.io.*; import java.nio.file.*; import java.util.*; import java.util.concurrent.locks.*;
public final class FileAtRepository implements AtRepository {
  private final Path dir; private final ReadWriteLock lock=new ReentrantReadWriteLock();
  public FileAtRepository(Path dir){this.dir=dir.toAbsolutePath().normalize();try{Files.createDirectories(this.dir);}catch(IOException e){throw new AtException("Cannot create AT directory",e);}}
  public void create(AtTransaction tx){lock.writeLock().lock();try{if(Files.exists(file(tx.getXid())))throw new AtException("Duplicate xid");write(tx);}finally{lock.writeLock().unlock();}}
  public Optional<AtTransaction> find(String xid){lock.readLock().lock();try{Path p=file(xid);if(!Files.exists(p))return Optional.empty();try(ObjectInputStream in=new ObjectInputStream(Files.newInputStream(p))){return Optional.of((AtTransaction)in.readObject());}catch(Exception e){throw new AtException("Cannot read "+xid,e);}}finally{lock.readLock().unlock();}}
  public void save(AtTransaction tx){lock.writeLock().lock();try{write(tx);}finally{lock.writeLock().unlock();}}
  public List<AtTransaction> recoverable(long now,int limit){List<AtTransaction> out=new ArrayList<AtTransaction>();try(DirectoryStream<Path>s=Files.newDirectoryStream(dir,"*.at")){for(Path p:s){AtTransaction tx=find(p.getFileName().toString().replace(".at","")).orElse(null);if(tx!=null&&((tx.getStatus()==AtStatus.ACTIVE&&tx.getDeadline()<=now)||tx.getStatus()==AtStatus.ROLLBACK_FAILED)&&tx.getNextRetryAt()<=now)out.add(tx);if(out.size()>=limit)break;}}catch(IOException e){throw new AtException("Cannot scan",e);}return out;}
  private void write(AtTransaction tx){Path target=file(tx.getXid()),tmp=target.resolveSibling(target.getFileName()+".tmp");try(FileOutputStream f=new FileOutputStream(tmp.toFile());ObjectOutputStream o=new ObjectOutputStream(f)){o.writeObject(tx);o.flush();f.getFD().sync();Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}catch(IOException e){throw new AtException("Cannot save "+tx.getXid(),e);}}
  private Path file(String xid){if(!xid.matches("[A-Za-z0-9-]+"))throw new IllegalArgumentException("Invalid xid");return dir.resolve(xid+".at");}
}
