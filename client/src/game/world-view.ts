import * as THREE from "three";
import { CSS2DObject, CSS2DRenderer } from "three/addons/renderers/CSS2DRenderer.js";
import type { EntityState } from "../gen/theages/v1/game_pb";

const CAMERA_OFFSET = new THREE.Vector3(0, 14, 12);
/** 位置平滑的速度（越大越快貼齊伺服器位置）。 */
const SMOOTHING = 12;

interface EntityView {
  group: THREE.Group;
  target: THREE.Vector3;
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
  private readonly timer = new THREE.Timer();
  private readonly raycaster = new THREE.Raycaster();
  private readonly entities = new Map<number, EntityView>();
  private readonly marker: THREE.Mesh;
  private ground: THREE.Mesh | null = null;
  private zoneRoot: THREE.Group | null = null;
  private selfId = 0;
  private frame = 0;
  private readonly resizeObserver: ResizeObserver;

  constructor(private readonly container: HTMLElement, private readonly onGroundClick: (x: number, z: number) => void) {
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.shadowMap.enabled = true;
    this.labels.domElement.className = "labels";
    container.append(this.renderer.domElement, this.labels.domElement);

    this.scene.background = new THREE.Color(0xb8c7d6);
    this.scene.fog = new THREE.Fog(0xb8c7d6, 30, 90);
    this.scene.add(new THREE.HemisphereLight(0xffffff, 0x556644, 1.6));
    const sun = new THREE.DirectionalLight(0xfff1d6, 2.2);
    sun.position.set(20, 30, 10);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    Object.assign(sun.shadow.camera, { left: -40, right: 40, top: 40, bottom: -40 });
    this.scene.add(sun);

    this.marker = new THREE.Mesh(
      new THREE.RingGeometry(0.3, 0.45, 32),
      new THREE.MeshBasicMaterial({ color: 0xffe08a, transparent: true, opacity: 0 }),
    );
    this.marker.rotation.x = -Math.PI / 2;
    this.marker.position.y = 0.02;
    this.scene.add(this.marker);

    this.renderer.domElement.addEventListener("pointerdown", (ev) => this.handlePointer(ev));
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(container);
    this.resize();
    this.renderer.setAnimationLoop(() => this.render());
  }

  /** 進入區域時建立地形。目前是程式產生的佔位場景，之後換成 glTF 模型。 */
  enterZone(selfId: number, size: number): void {
    this.selfId = selfId;
    this.entities.forEach((_, id) => this.remove(id));
    if (this.zoneRoot) {
      this.scene.remove(this.zoneRoot);
    }
    this.ground = new THREE.Mesh(
      new THREE.PlaneGeometry(size, size),
      new THREE.MeshStandardMaterial({ color: 0x7a9a5a }),
    );
    this.ground.rotation.x = -Math.PI / 2;
    this.ground.receiveShadow = true;
    this.zoneRoot = new THREE.Group().add(this.ground, createVillageProps(size));
    this.scene.add(this.zoneRoot);
  }

  upsert(state: EntityState): void {
    const pos = new THREE.Vector3(state.position?.x ?? 0, 0, state.position?.z ?? 0);
    const existing = this.entities.get(state.id);
    if (existing) {
      existing.target.copy(pos);
      return;
    }
    const group = createAvatar(state.name, state.id === this.selfId);
    group.position.copy(pos);
    this.scene.add(group);
    this.entities.set(state.id, { group, target: pos });
  }

  remove(id: number): void {
    const view = this.entities.get(id);
    if (!view) {
      return;
    }
    view.group.traverse((o) => {
      if (o instanceof CSS2DObject) {
        o.element.remove();
      }
    });
    this.scene.remove(view.group);
    this.entities.delete(id);
  }

  dispose(): void {
    this.renderer.setAnimationLoop(null);
    this.resizeObserver.disconnect();
    this.renderer.dispose();
    this.container.replaceChildren();
  }

  private handlePointer(ev: PointerEvent): void {
    if (ev.button !== 0 || !this.ground) {
      return;
    }
    const rect = this.renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(((ev.clientX - rect.left) / rect.width) * 2 - 1, -((ev.clientY - rect.top) / rect.height) * 2 + 1);
    this.raycaster.setFromCamera(ndc, this.camera);
    const hit = this.raycaster.intersectObject(this.ground)[0];
    if (hit) {
      this.marker.position.set(hit.point.x, 0.02, hit.point.z);
      (this.marker.material as THREE.MeshBasicMaterial).opacity = 1;
      this.onGroundClick(hit.point.x, hit.point.z);
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

    for (const { group, target } of this.entities.values()) {
      const dx = target.x - group.position.x;
      const dz = target.z - group.position.z;
      if (dx * dx + dz * dz > 1e-6) {
        group.rotation.y = Math.atan2(dx, dz);
      }
      group.position.lerp(target, alpha);
    }

    const self = this.entities.get(this.selfId);
    if (self) {
      const desired = self.group.position.clone().add(CAMERA_OFFSET);
      this.camera.position.lerp(desired, this.frame++ === 0 ? 1 : alpha * 0.5);
      this.camera.lookAt(self.group.position.x, 1, self.group.position.z);
    }

    const markerMat = this.marker.material as THREE.MeshBasicMaterial;
    markerMat.opacity = Math.max(0, markerMat.opacity - dt * 1.5);

    this.renderer.render(this.scene, this.camera);
    this.labels.render(this.scene, this.camera);
  }
}

function createAvatar(name: string, isSelf: boolean): THREE.Group {
  const group = new THREE.Group();
  const body = new THREE.Mesh(
    new THREE.CapsuleGeometry(0.35, 0.9, 4, 12),
    new THREE.MeshStandardMaterial({ color: isSelf ? 0xd9a441 : 0x4a78b5 }),
  );
  body.position.y = 0.8;
  body.castShadow = true;
  // 鼻子：讓人看得出面向
  const nose = new THREE.Mesh(new THREE.BoxGeometry(0.15, 0.15, 0.2), new THREE.MeshStandardMaterial({ color: 0x333333 }));
  nose.position.set(0, 1.25, 0.35);
  group.add(body, nose);

  const label = document.createElement("div");
  label.className = isSelf ? "name-label self" : "name-label";
  label.textContent = name;
  const labelObj = new CSS2DObject(label);
  labelObj.position.set(0, 2, 0);
  group.add(labelObj);
  return group;
}

/** 佔位用的村莊擺設：中央古井與周圍的樹（固定亂數種子，每位玩家看到的一樣）。 */
function createVillageProps(size: number): THREE.Group {
  const props = new THREE.Group();
  const stone = new THREE.MeshStandardMaterial({ color: 0x8a8a8a });
  const well = new THREE.Mesh(new THREE.CylinderGeometry(1.2, 1.3, 0.9, 20, 1, true), stone);
  well.position.y = 0.45;
  well.castShadow = true;
  props.add(well);

  let seed = 4444;
  const random = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
  const trunk = new THREE.MeshStandardMaterial({ color: 0x6b4a2b });
  const leaves = new THREE.MeshStandardMaterial({ color: 0x3f6b35 });
  for (let i = 0; i < 40; i++) {
    const angle = random() * Math.PI * 2;
    const radius = 8 + random() * (size / 2 - 10);
    const tree = new THREE.Group();
    const t = new THREE.Mesh(new THREE.CylinderGeometry(0.15, 0.2, 1.2), trunk);
    t.position.y = 0.6;
    const l = new THREE.Mesh(new THREE.ConeGeometry(0.9, 2.2, 8), leaves);
    l.position.y = 2.1;
    t.castShadow = l.castShadow = true;
    tree.add(t, l);
    tree.position.set(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    tree.scale.setScalar(0.8 + random() * 0.6);
    props.add(tree);
  }
  return props;
}
