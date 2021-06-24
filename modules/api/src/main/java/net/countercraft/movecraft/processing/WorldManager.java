package net.countercraft.movecraft.processing;

import net.countercraft.movecraft.processing.effects.Effect;
import net.countercraft.movecraft.util.CompletableFutureTask;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 *
 */
public final class WorldManager {

    public static final WorldManager INSTANCE = new WorldManager();
    private static final Runnable POISON = new Runnable() {
        @Override
        public void run() {/* No-op */}
        @Override
        public String toString(){
            return "POISON TASK";
        }
    };

    private final ConcurrentLinkedQueue<Effect> worldChanges = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Supplier<Effect>> tasks = new ConcurrentLinkedQueue<>();
    private final BlockingQueue<Runnable> currentTasks = new LinkedBlockingQueue<>();
    private volatile boolean running = false;

    private WorldManager(){}

    public void run() {
        if(!Bukkit.isPrimaryThread()){
            throw new RuntimeException("WorldManager must be executed on the main thread.");
        }
        if(tasks.isEmpty())
            return;
        running = true;
        Runnable runnable;
        int remaining = tasks.size();
        List<CompletableFuture<Effect>> inProgress = new ArrayList<>();
        while(!tasks.isEmpty()){
            inProgress.add(CompletableFuture.supplyAsync(tasks.poll()).whenComplete((effect, exception) -> {
                poison();
                if(exception != null){
                    exception.printStackTrace();
                } else if(effect != null) {
                    worldChanges.add(effect);
                }
            }));
        }
        // process pre-queued tasks and their requests to the main thread
        while(true){
            try {
                runnable = currentTasks.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                continue;
            }
            if(runnable == null){
                Bukkit.getLogger().severe("WorldManager timed out on task query! Dumping " + inProgress.size() + " tasks.");
                inProgress.forEach(task -> task.cancel(true));
                worldChanges.clear();
                break;
            }
            if(runnable == POISON){
                remaining--;
                if(remaining == 0){
                    break;
                }
            }
            runnable.run();
        }
        // process world updates on the main thread
        Effect sideEffect;
        while((sideEffect = worldChanges.poll()) != null){
            sideEffect.run();
        }
        CachedMovecraftWorld.purge();
        running = false;
    }

    public <T> T executeMain(@NotNull Supplier<T> callable){
        if(Bukkit.isPrimaryThread()){
            throw new RuntimeException("Cannot schedule on main thread from the main thread");
        }
        var task = new CompletableFutureTask<>(callable);
        currentTasks.add(task);
        return task.join();
    }

    public void executeMain(@NotNull Runnable runnable){
        this.executeMain(() -> {
            runnable.run();
            return null;
        });
    }

    private void poison(){
        currentTasks.add(POISON);
    }

    public void submit(Runnable task){
        tasks.add(() -> {
            task.run();
            return null;
        });
    }

    public void submit(Supplier<Effect> task){
        tasks.add(task);
    }

    public boolean isRunning() {
        return running;
    }
}
