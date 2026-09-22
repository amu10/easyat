package io.github.easyat.core;
import java.util.*;
public interface AtRepository { void create(AtTransaction tx); Optional<AtTransaction> find(String xid); void save(AtTransaction tx); List<AtTransaction> recoverable(long now,int limit); }
