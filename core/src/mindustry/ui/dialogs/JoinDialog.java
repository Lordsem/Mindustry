package mindustry.ui.dialogs;

import arc.*;
import arc.freetype.FreeTypeFontGenerator.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.*;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Timer.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.io.versions.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

public class JoinDialog extends BaseDialog{
    Seq<ServerGroup> tmpServers = new Seq<>();
    Seq<Server> servers = new Seq<>();
    Dialog add;
    Server renaming;
    Table local = new Table();
    Table remote = new Table();
    Table global = new Table();
    Table hosts = new Table();
    int totalHosts;
    int refreshes;
    boolean showHidden;
    TextButtonStyle style;
    Task fontIgnoreDirtyTask;

    String lastIp;
    int lastPort, lastColumns = -1;
    Task ping;

    String serverSearch = "";

    public JoinDialog(){
        super("@joingame");

        makeButtonOverlay();

        style = new TextButtonStyle(){{
            over = Styles.flatOver;
            font = Fonts.def;
            fontColor = Color.white;
            disabledFontColor = Color.gray;
            down = Styles.flatOver;
            up = Styles.black5;
        }};

        loadServers();

        //mobile players don't get information >:(
        boolean infoButton = !steam && !mobile;

        if(infoButton) buttons.add().width(60f);
        buttons.add().growX().width(-1);

        addCloseButton(mobile ? 190f : 210f);

        buttons.button("@server.add", Icon.add, () -> {
            renaming = null;
            add.show();
        });

        buttons.add().growX().width(-1);
        if(infoButton) buttons.button("?", () -> ui.showInfo("@join.info")).size(60f, 64f);

        add = new BaseDialog("@joingame.title");
        add.cont.add("@joingame.ip").padRight(5f).left();

        TextField field = add.cont.field(Core.settings.getString("ip"), text -> {
            Core.settings.put("ip", text);
        }).size(320f, 54f).maxTextLength(100).get();

        add.cont.row();
        add.buttons.defaults().size(140f, 60f).pad(4f);
        add.buttons.button("@cancel", add::hide);
        add.buttons.button("@ok", () -> {
            if(renaming == null){
                Server server = new Server();
                server.setIP(Core.settings.getString("ip"));
                servers.add(server);
            }else{
                renaming.setIP(Core.settings.getString("ip"));
            }
            saveServers();
            setupRemote();
            refreshRemote();
            add.hide();
        }).disabled(b -> Core.settings.getString("ip").isEmpty() || net.active());

        add.shown(() -> {
            add.title.setText(renaming != null ? "@server.edit" : "@server.add");
            if(renaming != null){
                field.setText(renaming.displayIP());
            }
        });

        keyDown(KeyCode.f5, this::refreshAll);

        shown(() -> {
            setup();
            refreshAll();

            if(!steam){
                Core.app.post(() -> Core.settings.getBoolOnce("joininfo", () -> ui.showInfo("@join.info")));
            }
        });

        onResize(() -> {
            //only refresh on resize when the minimum dimension is smaller than the maximum preferred width
            //this means that refreshes on resize will only happen for small phones that need the list to fit in portrait mode
            //also resize if number of cols changes
            if(Math.min(Core.graphics.getWidth(), Core.graphics.getHeight()) / Scl.scl() * 0.9f < 500f || lastColumns != columns()){
                setup();
                refreshAll();
            }

            lastColumns = columns();
        });
    }

    void refreshAll(){
        refreshes ++;

        refreshLocal();
        refreshRemote();
        if(Core.settings.getBool("communityservers", true)){
            refreshCommunity();
        }
    }

    void setupRemote(){
        remote.clear();

        for(Server server : servers){
            //why are java lambdas this bad
            Button[] buttons = {null};

            Button button = buttons[0] = remote.button(b -> {}, style, () -> {
                if(!buttons[0].childrenPressed()){
                    if(server.lastHost != null){
                        Events.fire(new ClientPreConnectEvent(server.lastHost));
                        safeConnect(server.lastHost.address, server.lastHost.port, server.lastHost.version);
                    }else{
                        connect(server.ip, server.port);
                    }
                }
            }).width(targetWidth()).growY().top().left().pad(4f).get();

            if(remote.getChildren().size % columns() == 0){
                remote.row();
            }

            Table inner = new Table(Tex.whiteui);
            inner.setColor(Pal.gray);

            button.clearChildren();
            button.add(inner).growX();

            inner.add("[accent]" + server.displayIP()).left().padLeft(10f).wrap().style(Styles.outlineLabel).growX();

            inner.button(Icon.upOpen, Styles.emptyi, () -> {
                moveRemote(server, -1);

            }).margin(3f).padTop(6f).top().right();

            inner.button(Icon.downOpen, Styles.emptyi, () -> {
                moveRemote(server, +1);

            }).margin(3f).pad(2).padTop(6f).top().right();

            inner.button(Icon.refresh, Styles.emptyi, () -> {
                refreshServer(server);
            }).margin(3f).pad(2).padTop(6f).top().right();

            inner.button(Icon.pencil, Styles.emptyi, () -> {
                renaming = server;
                add.show();
            }).margin(3f).pad(2).padTop(6f).top().right();

            inner.button(Icon.trash, Styles.emptyi, () -> {
                ui.showConfirm("@confirm", "@server.delete", () -> {
                    servers.remove(server, true);
                    saveServers();
                    setupRemote();
                    refreshRemote();
                });
            }).margin(3f).pad(2).pad(6).top().right();

            button.row();

            server.content = button.table(t -> {}).grow().get();
        }
    }

    void moveRemote(Server server, int sign){
        int index = servers.indexOf(server);

        if(index + sign < 0) return;
        if(index + sign > servers.size - 1) return;

        servers.remove(index);
        servers.insert(index + sign, server);

        saveServers();
        setupRemote();
        for(Server other : servers){
            if(other.lastHost != null){
                setupServer(other, other.lastHost);
            }else{
                refreshServer(other);
            }
        }
    }

    void refreshRemote(){
        for(Server server : servers){
            refreshServer(server);
        }
    }

    void refreshServer(Server server){
        server.content.clear();

        server.content.background(Tex.whitePane).setColor(Pal.gray);

        server.content.label(() -> Core.bundle.get("server.refreshing") + Strings.animated(Time.time, 4, 11, ".")).grow().center().labelAlign(Align.center).padBottom(4);

        net.pingHost(server.ip, server.port, host -> setupServer(server, host), e -> {
            server.content.clear();

            server.content.background(Tex.whitePane).setColor(Pal.gray);
            server.content.add("@host.invalid").grow().center().labelAlign(Align.center);
        });
    }

    void setupServer(Server server, Host host){
        server.lastHost = host;
        server.content.clear();
        buildServer(host, server.content, false, true);
    }

    void buildServer(Host host, Table content, boolean local, boolean addName){
        content.top().left();
        boolean isBanned = local && Vars.steam && host.description != null && host.description.equals("[banned]");
        String versionString = getVersionString(host) + (isBanned ? "[red] [banned]" : "");

        float twidth = targetWidth() - 40f;

        content.background(null);

        Color color = Pal.gray;

        if(addName){
            content.table(Tex.whiteui, t -> {
                t.left();
                t.setColor(color);

                t.add(host.name + "   " + versionString).style(Styles.outlineLabel).padLeft(10f).width(twidth).left().ellipsis(true);
            }).growX().height(36f).row();
        }

        content.table(Tex.whitePane, t -> {
            t.top().left();
            t.setColor(color);
            t.left();

            if(!host.description.isEmpty() && !isBanned){
                //limit newlines.
                int count = 0;
                StringBuilder result = new StringBuilder(host.description.length());
                for(int i = 0; i < host.description.length(); i++){
                    char c = host.description.charAt(i);
                    if(c == '\n'){
                        count ++;
                        if(count < 3) result.append(c);
                    }else{
                        result.append(c);
                    }
                }
                t.add("[gray]" + result).width(twidth).left().wrap();
                t.row();
            }

            t.add("[lightgray]" + (Core.bundle.format("players" + (host.players == 1 && host.playerLimit <= 0 ? ".single" : ""),
                (host.players == 0 ? "[lightgray]" : "[accent]") + host.players + (host.playerLimit > 0 ? "[lightgray]/[accent]" + host.playerLimit : "")+ "[lightgray]"))).left().row();

            t.add("[lightgray]" + Core.bundle.format("save.map", host.mapname) + "[lightgray] / " + (host.modeName == null ? host.mode.toString() : host.modeName)).width(twidth).left().ellipsis(true).row();

            if(host.ping > 0){
                t.add(Iconc.chartBar + " " + host.ping + "ms").style(Styles.outlineLabel).color(Pal.gray).left();
            }
        }).growY().growX().left().bottom();
    }

    void setup(){
        local.clear();
        remote.clear();
        global.clear();
        float w = targetWidth();

        hosts.clear();
        //since the buttons are an overlay, make room for that
        hosts.marginBottom(70f);

        section(steam ? "@servers.local.steam" : "@servers.local", local, false);
        section("@servers.remote", remote, false);
        if(Core.settings.getBool("communityservers", true)){
            section("@servers.global", global, true);
        }

        ScrollPane pane = new ScrollPane(hosts);
        pane.setFadeScrollBars(false);
        pane.setScrollingDisabled(true, false);

        setupRemote();

        cont.clear();
        cont.table(t -> {
            t.add("@name").padRight(10);
            t.field(Core.settings.getString("name"), text -> {
                player.name(text);
                Core.settings.put("name", text);
            }).grow().pad(8).maxTextLength(maxNameLength);

            ImageButton button = t.button(Tex.whiteui, Styles.squarei, 40, () -> {
                new PaletteDialog().show(color -> {
                    player.color().set(color);
                    Core.settings.put("color-0", color.rgba8888());
                });
            }).size(54f).get();
            button.update(() -> button.getStyle().imageUpColor = player.color());
        }).width(w).height(70f).pad(4);
        cont.row();
        cont.add(pane).width((w + 5) * columns() + 33).pad(0);
        cont.row();
    }

    void section(String label, Table servers, boolean eye){
        Collapser coll = new Collapser(servers, Core.settings.getBool("collapsed-" + label, false));
        coll.setDuration(0.1f);

        hosts.table(name -> {
            name.add(label).pad(10).growX().left().color(Pal.accent);

            if(eye){
                name.button(Icon.eyeSmall, Styles.emptyi, () -> {
                    showHidden = !showHidden;
                    refreshCommunity();
                }).update(i -> i.getStyle().imageUp = (showHidden ? Icon.eyeSmall : Icon.eyeOffSmall))
                    .size(40f).right().padRight(3).tooltip("@servers.showhidden");
            }

            name.button(Icon.downOpen, Styles.emptyi, () -> {
                coll.toggle(false);
                Core.settings.put("collapsed-" + label, coll.isCollapsed());
            }).update(i -> i.getStyle().imageUp = (!coll.isCollapsed() ? Icon.upOpen : Icon.downOpen)).size(40f).right().padRight(10f);
        }).growX();
        hosts.row();
        hosts.image().growX().pad(5).padLeft(10).padRight(10).height(3).color(Pal.accent);
        hosts.row();
        hosts.add(coll).width((targetWidth() + 5f) * columns());
        hosts.row();
    }

    void refreshLocal(){
        totalHosts = 0;

        local.clear();
        local.background(null);
        local.table(Tex.button, t -> t.label(() -> "[accent]" + Core.bundle.get("hosts.discovering.any") + Strings.animated(Time.time, 4, 10f, ".")).pad(10f)).growX();
        net.discoverServers(this::addLocalHost, this::finishLocalHosts);
    }

    void refreshCommunity(){
        int cur = refreshes;

        global.clear();
        global.background(null);

        if(!fetchedServers){
            fetchServers();
        }

        global.table(t -> {
            t.add("@search").padRight(10);
            t.field(serverSearch, text ->
                serverSearch = text.trim().replaceAll(" +", " ").toLowerCase()
            ).grow().pad(8).get().keyDown(KeyCode.enter, this::refreshCommunity);
            t.button(Icon.zoom, Styles.emptyi, this::refreshCommunity).size(54f);
        }).width((targetWidth() + 5f) * columns()).height(70f).pad(4).row();

        //if the servers have been fetched, use the fetched list
        //otherwise use the cached list + the extra servers that may have been included by mods
        var servers = fetchedServers ? defaultServers : tmpServers.clear().addAll(cachedServers).addAll(defaultServers);

        for(int i = 0; i < servers.size; i ++){
            ServerGroup group = servers.get((i + servers.size/2) % servers.size);
            boolean hidden = group.hidden();
            if(hidden && !showHidden){
                continue;
            }

            Table[] groupTable = {null, null};

            boolean favorite = group.favorite();
            if(group.prioritized){
                addHeader(groupTable, group, hidden, favorite, false);
            }else if(favorite){
                addHeader(groupTable, group, hidden, true, true);//weird behaviour if false?
            }
            //table containing all groups
            for(String address : group.addresses){
                String resaddress = address.contains(":") ? address.split(":")[0] : address;
                int resport = address.contains(":") ? Strings.parseInt(address.split(":")[1]) : port;
                net.pingHost(resaddress, resport, res -> {
                    if(refreshes != cur) return;

                    //don't recache the texture for a while
                    if(fontIgnoreDirtyTask == null){
                        FreeTypeFontData.ignoreDirty = true;
                        fontIgnoreDirtyTask = Time.runTask(0.6f * 60f, () -> {
                            FreeTypeFontData.ignoreDirty = false;
                            fontIgnoreDirtyTask = null;
                        });
                    }

                    if(!serverSearch.isEmpty() && !(group.name.toLowerCase().contains(serverSearch)
                        || res.name.toLowerCase().contains(serverSearch)
                        || res.description.toLowerCase().contains(serverSearch)
                        || res.mapname.toLowerCase().contains(serverSearch)
                        || (res.modeName != null && res.modeName.toLowerCase().contains(serverSearch)))) return;

                    if(groupTable[0] == null){
                        addHeader(groupTable, group, hidden, favorite, true);
                    }else if(!groupTable[0].visible){
                        addHeader(groupTable, group, hidden, favorite, true);
                    }

                    addCommunityHost(res, groupTable[1]);

                    groupTable[0].margin(5f);
                    groupTable[0].pack();
                }, e -> {});
            }
        }
    }

    void addHeader(Table[] groupTable, ServerGroup group, boolean hidden, boolean favorite, boolean doInit){ // outlined separately
        if(groupTable[0] == null){
            global.table(t -> groupTable[0] = t).fillX().left().row();
        }
        groupTable[0].visible(() -> doInit);
        if(!doInit){
            return;
        }

        groupTable[0].table(head -> {
            Color col = group.prioritized ? Pal.accent : Color.lightGray;
            if(!group.name.isEmpty()){
                head.add(group.name).color(col).padRight(4);
            }
            head.image().height(3f).growX().color(col);

            //button for showing/hiding servers
            ImageButton[] image = {null, null};
            image[0] = head.button(Icon.star, new ImageButton.ImageButtonStyle(){{
                imageUpColor = favorite ? Pal.accent : Color.lightGray;
                imageDownColor = Color.white;
            }}, () -> {
                group.setFavorite(!group.favorite());
                image[0].getStyle().imageUpColor = group.favorite() ? Pal.accent : Pal.lightishGray;
            }).size(40f).get();
            image[0].getStyle().imageUpColor = favorite ? Pal.accent : Pal.lightishGray;

            //button for showing/hiding servers
            image[1] = head.button(hidden ? Icon.eyeOffSmall : Icon.eyeSmall, Styles.grayi, () -> {
               group.setHidden(!group.hidden());
               image[1].getStyle().imageUp = group.hidden() ? Icon.eyeOffSmall : Icon.eyeSmall;
               if(group.hidden() && !showHidden){
                   groupTable[0].remove();
               }
            }).size(40f).get();
            image[1].addListener(new Tooltip(t -> t.background(Styles.black6).margin(4).label(() -> !group.hidden() ? "@server.shown" : "@server.hidden")));
        }).width(targetWidth() * columns()).padBottom(-2).row();

        groupTable[1] = groupTable[0].row().table().top().left().grow().get();
    }

    int columns(){
        return Mathf.clamp((int)((Core.graphics.getWidth() / Scl.scl() * 0.9f) / targetWidth()), 1, 4);
    }

    void addCommunityHost(Host host, Table container){
        global.background(null);
        String versionString = getVersionString(host);
        float w = targetWidth();

        container.left().top();

        Button[] button = {null};

        button[0] = container.button(b -> {}, style, () -> {
            if(button[0].childrenPressed()) return;

            Events.fire(new ClientPreConnectEvent(host));
            if(!Core.settings.getBool("server-disclaimer", false)){
                ui.showCustomConfirm("@warning", "@servers.disclaimer", "@ok", "@back", () -> {
                    Core.settings.put("server-disclaimer", true);
                    safeConnect(host.address, host.port, host.version);
                }, () -> {
                    Core.settings.put("server-disclaimer", false);
                });
            }else{
                safeConnect(host.address, host.port, host.version);
            }
        }).width(w).padBottom(7).padRight(4f).top().left().growY().uniformY().get();

        Table inner = new Table(Tex.whiteui);
        inner.setColor(Pal.gray);

        button[0].clearChildren();
        button[0].add(inner).height(45f).growX();

        inner.add(host.name + "   " + versionString).left().padLeft(10f).wrap().style(Styles.outlineLabel).growX();

        inner.button(Icon.add, Styles.emptyi, () -> {
            Server server = new Server();
            server.setIP(host.address + ":" + host.port);
            servers.add(server);
            saveServers();
            setupRemote();
            refreshRemote();
        }).margin(3f).pad(8f).padRight(4f).top().right();

        button[0].row();

        buildServer(host, button[0].table(t -> {}).grow().get(), false, false);

        if((container.getChildren().size) % columns() == 0){
            container.row();
        }
    }

    void finishLocalHosts(){
        if(totalHosts == 0){
            local.clear();
            local.background(Tex.button);
            local.add("@hosts.none").pad(10f);
            local.add().growX();
            local.button(Icon.refresh, this::refreshLocal).pad(-12f).padLeft(0).size(70f);
        }else{
            local.background(null);
        }
    }

    void addLocalHost(Host host){
        if(totalHosts == 0){
            local.clear();
        }
        local.background(null);
        totalHosts++;
        float w = targetWidth();

        if((local.getChildren().size) % columns() == 0){
            local.row();
        }

        local.button(b -> buildServer(host, b, true, true), style, () -> {
            Events.fire(new ClientPreConnectEvent(host));
            safeConnect(host.address, host.port, host.version);
        }).width(w).top().left().growY();
    }

    public void connect(String ip, int port){
        if(player.name.trim().isEmpty()){
            ui.showInfo("@noname");
            return;
        }

        ui.loadfrag.show("@connecting");

        ui.loadfrag.setButton(() -> {
            ui.loadfrag.hide();
            netClient.disconnectQuietly();
        });

        Time.runTask(2f, () -> {
            logic.reset();
            net.reset();
            Vars.netClient.beginConnecting();
            net.connect(lastIp = ip, lastPort = port, () -> {
                if(net.client()){
                    hide();
                    add.hide();
                }
            });
        });
    }

    public void reconnect(){
        if(lastIp == null || lastIp.isEmpty()) return;
        ui.loadfrag.show("@reconnecting");

        ping = Timer.schedule(() -> {
            net.pingHost(lastIp, lastPort, host -> {
                if(ping == null) return;
                ping.cancel();
                ping = null;
                connect(lastIp, lastPort);
            }, exception -> {});
        }, 1, 1);

        ui.loadfrag.setButton(() -> {
            ui.loadfrag.hide();
            if(ping == null) return;
            ping.cancel();
            ping = null;
        });
    }

    void safeConnect(String ip, int port, int version){
        if(version != Version.build && Version.build != -1 && version != -1){
            ui.showInfo("[scarlet]" + (version > Version.build ? KickReason.clientOutdated : KickReason.serverOutdated) + "\n[]" +
                Core.bundle.format("server.versions", Version.build, version));
        }else{
            connect(ip, port);
        }
    }

    float targetWidth(){
        return Math.min(Core.graphics.getWidth() / Scl.scl() * 0.9f, 550f);
    }

    @SuppressWarnings("unchecked")
    private void loadServers(){
        servers = Core.settings.getJson("servers", Seq.class, Server.class, Seq::new);

        //load imported legacy data
        if(Core.settings.has("server-list")){
            servers = LegacyIO.readServers();
            Core.settings.remove("server-list");
        }

        fetchServers();
    }

    public static void fetchServers(){
        var urls = Version.type.equals("bleeding-edge") || Vars.forceBeServers ? serverJsonBeURLs : serverJsonURLs;

        if(Core.settings.getBool("communityservers", true)){
            try{
                if(!loadedServerCache && serverCacheFile.exists()){
                    loadedServerCache = true;
                    cachedServers.addAll(parseServerString(serverCacheFile.readString()));
                }
            }catch(Exception e){
                Log.err("Failed to load cached server file", e);
            }

            fetchServers(urls, 0);
        }
    }

    private static void fetchServers(String[] urls, int index){
        if(index >= urls.length) return;

        Http.get(urls[index])
        .error(t -> {
            if(fetchedServers) return;

            if(index < urls.length - 1){
                //attempt fetching from the next URL upon failure
                fetchServers(urls, index + 1);
            }else{
                Log.err("Failed to fetch community servers", t);
            }
        })
        .submit(result -> {
            if(fetchedServers) return;

            String text = result.getResultAsString();
            Seq<ServerGroup> servers = parseServerString(text);
            //modify default servers on main thread
            Core.app.post(() -> {
                if(fetchedServers) return;

                //cache the server list to a file, so it can be loaded in case of an outage later
                try{
                    serverCacheFile.writeString(text);
                }catch(Exception e){
                    Log.err("Failed to write server cache", e);
                }
                defaultServers.addAll(servers);
                fetchedServers = true;
                Log.info("Fetched @ community servers.", defaultServers.sum(s -> s.addresses.length));
            });
        });
    }

    private static Seq<ServerGroup> parseServerString(String str){
        Jval val = Jval.read(str);
        Seq<ServerGroup> servers = new Seq<>();
        val.asArray().each(child -> {
            String name = child.getString("name", "");
            boolean prioritized = child.getBool("prioritized", false);
            String[] addresses;
            if(child.has("addresses") || (child.has("address") && child.get("address").isArray())){
                addresses = (child.has("addresses") ? child.get("addresses") : child.get("address")).asArray().map(Jval::asString).toArray(String.class);
            }else{
                addresses = new String[]{child.getString("address", "<invalid>")};
            }
            servers.add(new ServerGroup(name, addresses, prioritized));
        });
        servers.sort(s -> s.name == null ? Integer.MAX_VALUE : s.name.hashCode());
        return servers;
    }

    private void saveServers(){
        Core.settings.putJson("servers", Server.class, servers);
    }

    private String getVersionString(Host host){
        if(host.version == -1){
            return Core.bundle.format("server.version", Core.bundle.get("server.custombuild"), "");
        }else if(host.version == 0){
            return Core.bundle.get("server.outdated");
        }else if(host.version < Version.build && Version.build != -1){
            return Core.bundle.get("server.outdated") + "\n" +
            Core.bundle.format("server.version", host.version, "");
        }else if(host.version > Version.build && Version.build != -1){
            return Core.bundle.get("server.outdated.client") + "\n" +
            Core.bundle.format("server.version", host.version, "");
        }else if(host.version == Version.build && Version.type.equals(host.versionType)){
            //not important
            return "";
        }else{
            return Core.bundle.format("server.version", host.version, host.versionType);
        }
    }

    public static class Server{
        public String ip;
        public int port;

        transient Table content;
        transient Host lastHost;

        void setIP(String ip){
            try{
                boolean isIpv6 = Strings.count(ip, ':') > 1;
                if(isIpv6 && ip.lastIndexOf("]:") != -1 && ip.lastIndexOf("]:") != ip.length() - 1){
                    int idx = ip.indexOf("]:");
                    this.ip = ip.substring(1, idx);
                    this.port = Integer.parseInt(ip.substring(idx + 2));
                }else if(!isIpv6 && ip.lastIndexOf(':') != -1 && ip.lastIndexOf(':') != ip.length() - 1){
                    int idx = ip.lastIndexOf(':');
                    this.ip = ip.substring(0, idx);
                    this.port = Integer.parseInt(ip.substring(idx + 1));
                }else{
                    this.ip = ip;
                    this.port = Vars.port;
                }
            }catch(Exception e){
                this.ip = ip;
                this.port = Vars.port;
            }
        }

        String displayIP(){
            if(Strings.count(ip, ':') > 1){
                return port != Vars.port ? "[" + ip + "]:" + port : ip;
            }else{
                return ip + (port != Vars.port ? ":" + port : "");
            }
        }

        public Server(){
        }
    }
}
