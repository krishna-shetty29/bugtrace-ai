package com.bugtrace.worker;
import java.util.function.LongSupplier;
final class RetryBackoff {
 private final LongSupplier clock;private int failures;private long due;
 RetryBackoff(LongSupplier clock){this.clock=clock;}
 boolean ready(){return failures==0||clock.getAsLong()>=due;}
 void success(){failures=0;due=0;}
 void failed(){long seconds=Math.min(30,1L<<Math.min(failures,5));failures=Math.min(failures+1,6);due=clock.getAsLong()+seconds*1_000_000_000L;}
}
