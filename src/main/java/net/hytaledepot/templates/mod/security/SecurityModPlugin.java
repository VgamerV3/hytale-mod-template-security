package net.hytaledepot.templates.mod.security;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class SecurityModPlugin extends JavaPlugin {
  private enum Lifecycle {
    NEW,
    SETTING_UP,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
  }

  private final SecurityModTemplate service = new SecurityModTemplate();
  private final AtomicLong heartbeatTicks = new AtomicLong();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "hd-security-mod-worker");
            thread.setDaemon(true);
            return thread;
          });

  private volatile Lifecycle lifecycle = Lifecycle.NEW;
  private volatile ScheduledFuture<?> heartbeatTask;
  private volatile long startedAtEpochMillis;

  public SecurityModPlugin(JavaPluginInit init) {
    super(init);
  }

  @Override
  protected void setup() {
    lifecycle = Lifecycle.SETTING_UP;

    service.onInitialize(getDataDirectory());

    getCommandRegistry().registerCommand(new SecurityModStatusCommand());
    getCommandRegistry().registerCommand(new SecurityModDemoCommand());

    lifecycle = Lifecycle.RUNNING;
  }

  @Override
  protected void start() {
    startedAtEpochMillis = System.currentTimeMillis();

    heartbeatTask =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                long tick = heartbeatTicks.incrementAndGet();
                service.onHeartbeat(tick);
                if (tick % 60 == 0) {
                  getLogger().atInfo().log("[SecurityMod] heartbeat=%d", tick);
                }
              } catch (Exception exception) {
                lifecycle = Lifecycle.FAILED;
                service.incrementErrorCount();
                getLogger().atInfo().log("[SecurityMod] heartbeat failed: %s", exception.getMessage());
              }
            },
            1,
            1,
            TimeUnit.SECONDS);

    getTaskRegistry().registerTask(CompletableFuture.completedFuture(null));
  }

  @Override
  protected void shutdown() {
    lifecycle = Lifecycle.STOPPING;

    if (heartbeatTask != null) {
      heartbeatTask.cancel(true);
    }

    scheduler.shutdownNow();
    service.onShutdown();
    lifecycle = Lifecycle.STOPPED;
  }

  private long uptimeSeconds() {
    if (startedAtEpochMillis <= 0L) {
      return 0L;
    }
    return Math.max(0L, (System.currentTimeMillis() - startedAtEpochMillis) / 1000L);
  }

  private final class SecurityModStatusCommand extends CommandBase {
    private SecurityModStatusCommand() {
      super("hdsecuritymodstatus", "Shows runtime status for SecurityModPlugin.");
    setAllowsExtraArguments(true);
      this.setPermissionGroup(GameMode.Adventure);
    }

    @Override
    protected void executeSync(CommandContext ctx) {
      String sender = String.valueOf(ctx.sender().getDisplayName());
      String line =
          "[SecurityMod] lifecycle="
              + lifecycle
              + ", uptime="
              + uptimeSeconds()
              + "s"
              + ", heartbeatTicks="
              + heartbeatTicks.get()
              + ", heartbeatActive="
              + (heartbeatTask != null && !heartbeatTask.isCancelled() && !heartbeatTask.isDone())
              + ", "
              + service.diagnostics(sender, heartbeatTicks.get());
      ctx.sendMessage(Message.raw(line));
    }
  }

  private final class SecurityModDemoCommand extends CommandBase {
    private SecurityModDemoCommand() {
      super("hdsecuritymoddemo", "Runs a demo action for SecurityModPlugin.");
    setAllowsExtraArguments(true);
      this.setPermissionGroup(GameMode.Adventure);
    }

    @Override
    protected void executeSync(CommandContext ctx) {
      String action = parseAction(ctx.getInputString(), "sample");
      String sender = String.valueOf(ctx.sender().getDisplayName());

      String response = service.runAction(sender, action, heartbeatTicks.get());
      ctx.sendMessage(Message.raw(response));
    }
  }

  private static String parseAction(String input, String fallback) {
    String normalized = String.valueOf(input == null ? "" : input).trim();
    if (normalized.isEmpty()) {
      return fallback;
    }

    String[] parts = normalized.split("\\s+");
    String first = parts[0].toLowerCase();
    if (first.startsWith("/")) {
      first = first.substring(1);
    }

    if (parts.length > 1 && first.startsWith("hd")) {
      return parts[1].toLowerCase();
    }
    return first;
  }
}
