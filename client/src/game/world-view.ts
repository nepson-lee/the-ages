import * as THREE from "three";
import { CSS2DObject, CSS2DRenderer } from "three/addons/renderers/CSS2DRenderer.js";
import { EntityKind, QuestMarkerKind, type CombatEvent, type EntityState, type QuestMarkers } from "../gen/theages/v1/game_pb";
import { createItemModel, createModel, createPortalModel } from "./models";
import { themeFor } from "./zones";

const CAMERA_OFFSET = new THREE.Vector3(0, 14, 12);
/** 位置平滑的速度（越大越快貼齊伺服器位置）。 */
const SMOOTHING = 12;
const FLOATER_MS = 1000;

export interface WorldViewHandlers {
  onGroundClick(x: number, z: number): void;
  onEntityClick(id: number): void;
  onItemClick(id: number): void;
  /** 商人與友善 NPC：對話（任務、商店）。 */
  onTalkClick(id: number): void;
  onPortalClick(id: number): void;
}

interface EntityView {
  state: EntityState;
  group: THREE.Group;
  target: THREE.Vector3;
  labelHeight: number;
  label: HTMLElement;
  hpFill: HTMLElement;
}

/**
 * 3D 場景：只負責「顯示」伺服器送來的狀態，不做任何遊戲判定。
 * 伺服器 10 Hz 的位置更新在這裡以指數平滑補成 60 fps 的動畫。
 */
export class WorldView {
  private readonly renderer = new THREE.WebGLRenderer({ antialias: true });
  private readonly labels = new CSS2DRenderer();
  private readonly scene = new THREE.Scene();
  private readonly camera = new THREE.PerspectiveCamera(50, 1, 0.1, 500);
  private readonly hemisphere = new THREE.HemisphereLight(0xffffff, 0x556644, 1.6);
  private readonly timer = new THREE.Timer();
  private readonly raycaster = new THREE.Raycaster();
  private readonly entities = new Map<number, EntityView>();
  private readonly marker: THREE.Mesh;
  private readonly targetRing: THREE.Mesh;
  private ground: THREE.Mesh | null = null;
  private zoneRoot: THREE.Group | null = null;
  private selfId = 0;
  /** 任務標記（依自己的任務進度），entity id → 種類。 */
  private questMarkers = new Map<number, QuestMarkerKind>();
  private cameraPlaced = false;
  private readonly resizeObserver: ResizeObserver;

  constructor(private readonly container: HTMLElement, private readonly handlers: WorldViewHandlers) {
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.shadowMap.enabled = true;
    this.labels.domElement.className = "labels";
    container.append(this.renderer.domElement, this.labels.domElement);

    this.scene.background = new THREE.Color(0xb8c7d6);
    this.scene.fog = new THREE.Fog(0xb8c7d6, 30, 90);
    this.scene.add(this.hemisphere);
    const sun = new THREE.DirectionalLight(0xfff1d6, 2.2);
    sun.position.set(20, 30, 10);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    Object.assign(sun.shadow.camera, { left: -40, right: 40, top: 40, bottom: -40 });
    this.scene.add(sun);

    this.marker = groundRing(0.3, 0.45, 0xffe08a);
    (this.marker.material as THREE.MeshBasicMaterial).opacity = 0;
    this.targetRing = groundRing(0.55, 0.7, 0xe0453a);
    this.targetRing.visible = false;
    this.scene.add(this.marker, this.targetRing);

    this.renderer.domElement.addEventListener("pointerdown", (ev) => this.handlePointer(ev));
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(container);
    this.resize();
    this.renderer.setAnimationLoop(() => this.render());
  }

  /** 進入區域（包括換區）時清空場景，依區域主題重建地形。 */
  enterZone(selfId: number, zoneId: string, size: number): void {
    this.questMarkers.clear();
    const theme = themeFor(zoneId);
    (this.scene.background as THREE.Color).setHex(theme.sky);
    this.scene.fog = new THREE.Fog(theme.sky, theme.fogNear, theme.fogFar);
    this.hemisphere.intensity = theme.ambient;
    this.selfId = selfId;
    this.cameraPlaced = false;
    [...this.entities.keys()].forEach((id) => this.remove(id));
    if (this.zoneRoot) {
      this.scene.remove(this.zoneRoot);
    }
    this.ground = new THREE.Mesh(
      new THREE.PlaneGeometry(size, size),
      new THREE.MeshStandardMaterial({ color: theme.ground }),
    );
    this.ground.rotation.x = -Math.PI / 2;
    this.ground.receiveShadow = true;
    this.zoneRoot = new THREE.Group().add(this.ground, theme.props(size));
    this.scene.add(this.zoneRoot);
  }

  upsert(state: EntityState): void {
    const pos = new THREE.Vector3(state.position?.x ?? 0, 0, state.position?.z ?? 0);
    let view = this.entities.get(state.id);
    if (!view) {
      view = this.create(state, pos);
      this.entities.set(state.id, view);
    }
    view.state = state;
    view.target.copy(pos);
    this.updateLabel(view);
  }

  remove(id: number): void {
    const view = this.entities.get(id);
    if (!view) {
      return;
    }
    view.label.remove();
    this.scene.remove(view.group);
    this.entities.delete(id);
  }

  setQuestMarkers(markers: QuestMarkers): void {
    this.questMarkers = new Map(markers.markers.map((m) => [m.entityId, m.kind]));
    for (const view of this.entities.values()) {
      this.updateQuestMark(view);
    }
  }

  private updateQuestMark(view: EntityView): void {
    const kind = this.questMarkers.get(view.state.id);
    const mark = view.label.querySelector<HTMLElement>(".quest-mark")!;
    mark.textContent = kind === QuestMarkerKind.READY ? "?" : kind === QuestMarkerKind.AVAILABLE ? "!" : "";
    mark.className = `quest-mark${kind === QuestMarkerKind.READY ? " ready" : ""}`;
  }

  /** 在被攻擊者頭上飄出傷害數字。 */
  showCombat(event: CombatEvent): void {
    const target = this.entities.get(event.targetId);
    if (!target) {
      return;
    }
    const involvesSelf = event.targetId === this.selfId || event.attackerId === this.selfId;
    // 外層給 CSS2DRenderer 定位（它會改寫 transform），內層才做動畫
    const el = document.createElement("div");
    const text = el.appendChild(document.createElement("span"));
    const kind = event.heal ? "heal" : event.miss ? "miss" : event.targetId === this.selfId ? "hurt" : "hit";
    text.className = `floater ${kind}${event.skillName ? " skill" : ""}${involvesSelf ? "" : " dim"}`;
    const amount = event.miss ? "閃避" : event.heal ? `+${event.damage}` : `-${event.damage}`;
    text.textContent = event.skillName ? `${event.skillName} ${amount}` : amount;
    // 掛在場景上而不是實體上：目標被打死移除後數字仍會播完
    const obj = new CSS2DObject(el);
    obj.position.copy(target.group.position).setY(target.labelHeight + 0.3);
    this.scene.add(obj);
    setTimeout(() => {
      this.scene.remove(obj);
      el.remove();
    }, FLOATER_MS);
  }

  dispose(): void {
    this.renderer.setAnimationLoop(null);
    this.resizeObserver.disconnect();
    this.renderer.dispose();
    this.container.replaceChildren();
  }

  private create(state: EntityState, pos: THREE.Vector3): EntityView {
    const isSelf = state.id === this.selfId;
    const model = state.kind === EntityKind.ITEM ? createItemModel(state.model)
      : state.kind === EntityKind.PORTAL ? createPortalModel()
      : createModel(state.model, isSelf);
    const group = new THREE.Group().add(model.object);
    group.position.copy(pos);
    group.userData.entityId = state.id;

    const label = document.createElement("div");
    const kindClass = { [EntityKind.NPC]: "npc", [EntityKind.ITEM]: "item", [EntityKind.MERCHANT]: "merchant", [EntityKind.PORTAL]: "portal", [EntityKind.FRIENDLY]: "friendly" }[state.kind as number] ?? "player";
    label.className = `name-label ${kindClass}${isSelf ? " self" : ""}`;
    label.innerHTML = `<span class="quest-mark"></span><span class="name"></span><div class="hp"><div class="fill"></div></div>`;
    const labelObj = new CSS2DObject(label);
    labelObj.position.set(0, model.labelHeight, 0);
    group.add(labelObj);
    this.scene.add(group);

    const view = { state, group, target: pos.clone(), labelHeight: model.labelHeight, label, hpFill: label.querySelector<HTMLElement>(".fill")! };
    this.updateQuestMark(view);
    return view;
  }

  private updateLabel(view: EntityView): void {
    const s = view.state;
    view.label.querySelector(".name")!.textContent =
      s.kind === EntityKind.NPC ? `${s.name} Lv${s.level}` : s.kind === EntityKind.MERCHANT ? `${s.name}［商人］` : s.name;
    const ratio = s.maxHp > 0 ? s.hp / s.maxHp : 1;
    view.hpFill.style.width = `${Math.round(ratio * 100)}%`;
    // 滿血又不在戰鬥時隱藏血條，畫面比較乾淨
    const hasHp = s.kind === EntityKind.NPC || s.kind === EntityKind.PLAYER;
    view.label.classList.toggle("show-hp", hasHp && (ratio < 1 || s.targetId !== 0));
  }

  private handlePointer(ev: PointerEvent): void {
    if (ev.button !== 0 || !this.ground) {
      return;
    }
    const rect = this.renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(((ev.clientX - rect.left) / rect.width) * 2 - 1, -((ev.clientY - rect.top) / rect.height) * 2 + 1);
    this.raycaster.setFromCamera(ndc, this.camera);

    // 先看有沒有點到 NPC、商人或地上物品，沒有才算點地面
    const clickable = [...this.entities.values()].filter((v) => v.state.kind !== EntityKind.PLAYER);
    const hitEntity = this.raycaster.intersectObjects(clickable.map((v) => v.group), true)[0];
    if (hitEntity) {
      let o: THREE.Object3D | null = hitEntity.object;
      while (o && o.userData.entityId === undefined) {
        o = o.parent;
      }
      const view = o ? this.entities.get(o.userData.entityId as number) : undefined;
      if (view?.state.kind === EntityKind.ITEM) {
        this.handlers.onItemClick(view.state.id);
        return;
      }
      if (view?.state.kind === EntityKind.MERCHANT || view?.state.kind === EntityKind.FRIENDLY) {
        this.handlers.onTalkClick(view.state.id);
        return;
      }
      if (view?.state.kind === EntityKind.PORTAL) {
        this.handlers.onPortalClick(view.state.id);
        return;
      }
      if (view) {
        this.handlers.onEntityClick(view.state.id);
        return;
      }
    }
    const hit = this.raycaster.intersectObject(this.ground)[0];
    if (hit) {
      this.marker.position.set(hit.point.x, 0.02, hit.point.z);
      (this.marker.material as THREE.MeshBasicMaterial).opacity = 1;
      this.handlers.onGroundClick(hit.point.x, hit.point.z);
    }
  }

  private resize(): void {
    const { clientWidth: w, clientHeight: h } = this.container;
    if (w === 0 || h === 0) {
      return;
    }
    this.renderer.setSize(w, h);
    this.labels.setSize(w, h);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
  }

  private render(): void {
    this.timer.update();
    const dt = Math.min(this.timer.getDelta(), 0.1);
    const alpha = 1 - Math.exp(-SMOOTHING * dt);

    const now = performance.now() / 1000;
    for (const view of this.entities.values()) {
      const { group, target } = view;
      if (view.state.kind === EntityKind.ITEM) {
        const bob = group.getObjectByName("bob");
        if (bob) {
          bob.position.y = 0.3 + Math.sin(now * 2.5 + view.state.id) * 0.06;
          bob.rotation.y = now * 1.2;
        }
        continue;
      }
      if (view.state.kind === EntityKind.PORTAL) {
        group.getObjectByName("bob")?.scale.setScalar(1 + Math.sin(now * 2) * 0.04);
        continue;
      }
      const dx = target.x - group.position.x;
      const dz = target.z - group.position.z;
      // 移動時面向前進方向；原地戰鬥時面向對手
      const foe = view.state.targetId ? this.entities.get(view.state.targetId) : undefined;
      if (dx * dx + dz * dz > 1e-4) {
        group.rotation.y = Math.atan2(dx, dz);
      } else if (foe) {
        group.rotation.y = Math.atan2(foe.group.position.x - group.position.x, foe.group.position.z - group.position.z);
      }
      // 距離太遠（重生、瞬移）直接跳過去，不要滑過整張地圖
      if (dx * dx + dz * dz > 25) {
        group.position.copy(target);
      } else {
        group.position.lerp(target, alpha);
      }
    }

    const self = this.entities.get(this.selfId);
    if (self) {
      const desired = self.group.position.clone().add(CAMERA_OFFSET);
      this.camera.position.lerp(desired, this.cameraPlaced ? alpha * 0.5 : 1);
      this.cameraPlaced = true;
      this.camera.lookAt(self.group.position.x, 1, self.group.position.z);

      const foe = self.state.targetId ? this.entities.get(self.state.targetId) : undefined;
      this.targetRing.visible = foe !== undefined;
      if (foe) {
        this.targetRing.position.set(foe.group.position.x, 0.03, foe.group.position.z);
      }
    }

    const markerMat = this.marker.material as THREE.MeshBasicMaterial;
    markerMat.opacity = Math.max(0, markerMat.opacity - dt * 1.5);

    this.renderer.render(this.scene, this.camera);
    this.labels.render(this.scene, this.camera);
  }
}

function groundRing(inner: number, outer: number, color: number): THREE.Mesh {
  const ring = new THREE.Mesh(
    new THREE.RingGeometry(inner, outer, 32),
    new THREE.MeshBasicMaterial({ color, transparent: true }),
  );
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.02;
  return ring;
}
