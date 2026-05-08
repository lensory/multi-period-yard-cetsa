# Flow-based Compact Model for the Integrated Multi-period Yard Template and Handling Scheduling Problem

## 1. Purpose

This note reformulates the original integrated model in a more implementation-friendly **flow-based compact form**. The objective is to preserve the modeling logic of the original vessel-period formulation while reducing the number of redundant variables and constraints generated in Java/CPLEX.

The key idea is to replace the original vessel-level unloading activity structure with a **transshipment-flow index**. Each flow represents one valid vessel-period-to-vessel-period transshipment relation.

In the original model, a transshipment movement is indexed by

$$
(j,q)\rightarrow(i,p),
$$

where $(j,q)$ is the source vessel-period and $(i,p)$ is the destination vessel-period. In the compact model, each valid pair is represented by a single flow index

$$
f\in\mathcal F,
$$

with

$$
o(f)=(j,q),\qquad d(f)=(i,p).
$$

This reformulation is particularly suitable for a `ModelIndex` implementation in Java, where `vpId` and `flowId` are used as array indices.

---

## 2. Original-to-compact notation mapping

Let

$$
\mathcal P=\{u=(i,p): i\in V,\ p\in E^i\}
$$

be the set of all vessel-period pairs in the planning horizon. For any $u\in\mathcal P$, define

$$
v(u)
$$

as the parent vessel of $u$, and

$$
E_u\subseteq T
$$

as the set of time steps covered by vessel-period $u$. If $u=(i,p)$, then $v(u)=i$ and $E_u=E^i_p$.

The following table summarizes the main notation changes.

| Original notation | Compact notation | Meaning |
|---|---:|---|
| $(i,p)$ | $u\in\mathcal P$ | A vessel-period pair |
| $(j,q)\to(i,p)$ | $f\in\mathcal F$ | A transshipment flow |
| $F^S_{ip}$ | $\mathcal F^-(u)$ | Flows whose destination is $u$ |
| $F^D_{jq}$ | $\mathcal F^+(u)$ | Flows whose source is $u$ |
| $n_{jqip}$ | $n_f$ | Container volume of flow $f$ |
| $d_{jqkip}$ | $d_{fk}$ | Route length of flow $f$ via subblock $k$ |
| $h^U_{jqkr}$ | $h^U_{fkr}$ | Unloading route-lane incidence |
| $h^L_{ipkr}$ | $h^L_{ukr}$ | Loading route-lane incidence |
| $y_{ikp}$ | $y_{uk}$ | Whether subblock $k$ is assigned to vessel-period $u$ |
| $z_{jqkip}$ | $z_{fk}$ | Whether flow $f$ may use subblock $k$ |
| $w_{jqkip}$ | $w_{fk}$ | Containers of flow $f$ stored at subblock $k$ |
| $\delta^U_{jkt}x_{ikt}$ | $\delta^U_{fkt}$ | Unloading activity of flow $f$ at subblock $k$ and time $t$ |
| $\delta^L_{ikt}$ | $\delta^L_{ukt}$ | Loading activity of vessel-period $u$ at subblock $k$ and time $t$ |

---

## 3. Compact sets

### 3.1 Basic sets

$$
V:
\text{set of vessels}.
$$

$$
K:
\text{set of subblocks}.
$$

$$
T:
\text{set of time steps in the wrapped planning horizon}.
$$

$$
R:
\text{set of vertical passing lanes}.
$$

$$
\mathcal P:
\text{set of vessel-period pairs}.
$$

For each $u\in\mathcal P$, define

$$
E_u\subseteq T
$$

as the time interval occupied by vessel-period $u$ in the wrapped horizon.

---

### 3.2 Active vessel-period mapping

For each vessel $i\in V$ and time step $t\in T$, define

$$
\alpha(i,t)\in\mathcal P
$$

as the unique vessel-period of vessel $i$ that is active at time $t$. Thus,

$$
v(\alpha(i,t))=i,
\qquad
t\in E_{\alpha(i,t)}.
$$

This mapping is used to eliminate the original $x_{ikt}$ variables. In particular, the original assignment variable can be recovered as

$$
x_{ikt}=y_{\alpha(i,t),k}.
$$

Therefore, $x_{ikt}$ does not need to be explicitly created in the compact implementation.

---

### 3.3 Flow set

Define the transshipment flow set as

$$
\mathcal F
=
\{f=(o(f),d(f)): o(f),d(f)\in\mathcal P,\ n_f>0\}.
$$

Here,

$$
o(f):
\text{source vessel-period of flow } f,
$$

$$
d(f):
\text{destination vessel-period of flow } f,
$$

$$
n_f:
\text{number of containers transshipped by flow } f.
$$

For each vessel-period $u\in\mathcal P$, define

$$
\mathcal F^+(u)=\{f\in\mathcal F: o(f)=u\},
$$

$$
\mathcal F^-(u)=\{f\in\mathcal F: d(f)=u\}.
$$

Thus, $\mathcal F^+(u)$ is the set of flows unloaded from $u$, while $\mathcal F^-(u)$ is the set of flows loaded onto $u$.

---

### 3.4 Compact time-slot sets

For each flow $f\in\mathcal F$, define its unloading time-slot set as

$$
T^U_f\subseteq E_{o(f)}\cap E_{d(f)}.
$$

A conservative definition is

$$
T^U_f=E_{o(f)}\cap E_{d(f)}.
$$

A tighter implementation-side definition is

$$
T^U_f
=
\left\{
t\in E_{o(f)}\cap E_{d(f)}:
a^M_{o(f)}\le \gamma_{o(f)}(t)\le b^M_{o(f)}-1,\
\gamma_{d(f)}(t) \le b^M_{d(f)} - 1
\right\}.
$$

For each vessel-period $u\in\mathcal P$, define its loading time-slot set as

$$
T^L_u
=
\left\{
t\in E_u:
a^M_u\le \gamma_u(t)\le b^M_u-1
\right\}.
$$

In implementation, $T^U_f$ and $T^L_u$ are preferably stored as local time-slot arrays:

$$
T^U_f=\{t_{fs}:s=1,\ldots,|T^U_f|\},
$$

$$
T^L_u=\{t_{us}:s=1,\ldots,|T^L_u|\}.
$$

Then the CPLEX variables can be stored as

```java
deltaU[f][k][s]
deltaL[u][k][s]
```

rather than as full arrays over all $t\in T$.

---

## 4. Compact parameters

### 4.1 Yard-template parameters

$$
g_u:
\text{number of subblocks required by vessel-period } u.
$$

$$
C_k:
\text{capacity of subblock } k.
$$

$$
N_k:
\text{set of neighboring subblocks of } k.
$$

---

### 4.2 Transshipment and routing parameters

$$
n_f:
\text{number of containers of flow } f.
$$

$$
d_{fk}:
\text{route length of flow } f \text{ via subblock } k.
$$

If $f$ corresponds to $(j,q)\to(i,p)$, then

$$
d_{fk}=d_{jqkip}.
$$

$$
h^U_{fkr}
=
\begin{cases}
1, & \text{if the unloading route of flow } f \text{ via subblock } k \text{ passes lane } r,\\
0, & \text{otherwise}.
\end{cases}
$$

$$
h^L_{ukr}
=
\begin{cases}
1, & \text{if the loading route from subblock } k \text{ to vessel-period } u \text{ passes lane } r,\\
0, & \text{otherwise}.
\end{cases}
$$

Although $h^U_{fkr}$ depends only on $o(f)$ and $k$, storing it by flow $f$ is often convenient in a flow-based implementation.

---

### 4.3 Time-window parameters

$$
\gamma_u(t):
\text{relative time offset of time step } t \text{ with respect to the start of } u.
$$

$$
[a^M_u,b^M_u):
\text{feasible handling interval of vessel-period } u.
$$

$$
[a^E_u,b^E_u):
\text{expected handling interval of vessel-period } u.
$$

$$
c^E_u,\ c^T_u:
\text{earliness and tardiness costs of vessel-period } u.
$$

---

### 4.4 Congestion and objective parameters

$$
f^U_{\max}:
\text{maximum allowed unloading traffic flows on a vertical lane}.
$$

$$
f^L_{\max}:
\text{maximum allowed loading traffic flows on a vertical lane}.
$$

$$
\eta:
\text{weight of the route-distance term}.
$$

$$
P:
\text{penalty coefficient for lane-flow overload}.
$$

---

## 5. Compact decision variables

### 5.1 Yard-template variables

$$
y_{uk}\in\{0,1\},
\qquad
u\in\mathcal P,\ k\in K.
$$

$$
y_{uk}=1
$$

if and only if subblock $k$ is assigned to vessel-period $u$.

The original variable $x_{ikt}$ is eliminated and can be recovered by

$$
x_{ikt}=y_{\alpha(i,t),k}.
$$

---

### 5.2 Storage allocation variables

$$
z_{fk}\in\{0,1\},
\qquad
f\in\mathcal F,\ k\in K.
$$

$$
z_{fk}=1
$$

if and only if flow $f$ is allowed to use subblock $k$.

$$
w_{fk}\ge 0,
\qquad
f\in\mathcal F,\ k\in K.
$$

$$
w_{fk}
$$

is the number of containers of flow $f$ stored at subblock $k$.

If integer container assignment is required, impose

$$
w_{fk}\in\mathbb Z_+.
$$

For computational testing, $w_{fk}$ may also be treated as continuous.

---

### 5.3 Handling activity variables

$$
\delta^U_{fkt}\in\{0,1\},
\qquad
f\in\mathcal F,\ k\in K,\ t\in T^U_f.
$$

$$
\delta^U_{fkt}=1
$$

if and only if flow $f$ is unloaded to subblock $k$ at time step $t$.

$$
\delta^L_{ukt}\in\{0,1\},
\qquad
u\in\mathcal P,\ k\in K,\ t\in T^L_u.
$$

$$
\delta^L_{ukt}=1
$$

if and only if vessel-period $u$ loads containers from subblock $k$ at time step $t$.

---

### 5.4 Time and deviation variables

$$
\epsilon_u:
\text{start time of unloading activities of vessel-period } u.
$$

$$
\tau_u:
\text{start time of loading activities of vessel-period } u.
$$

$$
\sigma_u:
\text{end time of loading activities of vessel-period } u.
$$

$$
\iota_u\ge 0:
\text{earliness deviation of vessel-period } u.
$$

$$
\kappa_u\ge 0:
\text{tardiness deviation of vessel-period } u.
$$

---

### 5.5 Congestion variables

$$
\rho_{kt}\in\{0,1\},
\qquad
k\in K,\ t\in T.
$$

$$
\rho_{kt}=1
$$

if and only if subblock $k$ has loading or unloading activity at time step $t$.

$$
\theta^U\ge 0,\qquad \theta^L\ge 0.
$$

$$
\theta^U
$$

is the maximum overload of unloading lane flows, and

$$
\theta^L
$$

is the maximum overload of loading lane flows.

If one prefers to keep the original single overload variable, define instead

$$
\theta\ge 0
$$

and replace $\theta^U,\theta^L$ by $\theta$ in the lane-flow constraints and the objective.

---

## 6. Compact objective function

The route-distance term is

$$
\mathrm{obj}^{route}
=
\sum_{f\in\mathcal F}\sum_{k\in K}d_{fk}w_{fk}.
$$

The time-deviation term is

$$
\mathrm{obj}^{time}
=
\sum_{u\in\mathcal P}
\left(
c^E_u\iota_u+c^T_u\kappa_u
\right).
$$

The congestion penalty term is

$$
\mathrm{obj}^{cong}
=
P(\theta^U+\theta^L).
$$

The compact objective is

$$
\min
\quad
\eta
\sum_{f\in\mathcal F}\sum_{k\in K}d_{fk}w_{fk}
+
\sum_{u\in\mathcal P}
\left(
c^E_u\iota_u+c^T_u\kappa_u
\right)
+
P(\theta^U+\theta^L).
$$

If a single overload variable $\theta$ is used, replace the last term by $P\theta$.

---

## 7. Compact constraints

### 7.1 Yard-template constraints

Each subblock can be assigned to at most one active vessel-period at each time step:

$$
\sum_{u\in\mathcal P:t\in E_u}y_{uk}\le 1,
\qquad
\forall k\in K,\ t\in T.
$$

Equivalently, using the active vessel-period mapping,

$$
\sum_{i\in V}y_{\alpha(i,t),k}\le 1,
\qquad
\forall k\in K,\ t\in T.
$$

Each vessel-period receives the required number of subblocks:

$$
\sum_{k\in K}y_{uk}=g_u,
\qquad
\forall u\in\mathcal P.
$$

---

### 7.2 Storage allocation constraints

A flow can use only subblocks reserved for its destination vessel-period:

$$
z_{fk}\le y_{d(f),k},
\qquad
\forall f\in\mathcal F,\ k\in K.
$$

Container assignment is allowed only if the flow uses the subblock:

$$
w_{fk}\le C_k z_{fk},
\qquad
\forall f\in\mathcal F,\ k\in K.
$$

All containers of each flow must be assigned:

$$
\sum_{k\in K}w_{fk}=n_f,
\qquad
\forall f\in\mathcal F.
$$

The total containers stored in each subblock for a destination vessel-period cannot exceed the subblock capacity:

$$
\sum_{f\in\mathcal F^-(u)}w_{fk}\le C_k y_{uk},
\qquad
\forall u\in\mathcal P,\ k\in K.
$$

This is a strengthened version of the original subblock-capacity constraint because it also activates the capacity only when $y_{uk}=1$.

---

### 7.3 Unloading time assignment constraints

If flow $f$ uses subblock $k$, exactly one unloading time slot must be selected:

$$
\sum_{t\in T^U_f}\delta^U_{fkt}=z_{fk},
\qquad
\forall f\in\mathcal F,\ k\in K.
$$

This constraint replaces the original nonlinear structure

$$
\sum_{t\in E^i_p\cap E^j_q}\delta^U_{jkt}x_{ikt}=z_{jqkip}.
$$

The compact formulation does not require a product term because the destination vessel-period is embedded in $f$.

---

### 7.4 Loading time assignment constraints

If subblock $k$ is reserved for vessel-period $u$, exactly one loading time slot must be selected:

$$
\sum_{t\in T^L_u}\delta^L_{ukt}=y_{uk},
\qquad
\forall u\in\mathcal P,\ k\in K.
$$

---

### 7.5 Unloading start-time constraints

For every selected unloading activity, the unloading start time of the source vessel-period should be no later than that activity:

$$
\epsilon_{o(f)}
\le
\gamma_{o(f)}(t)
+
M^{\epsilon,U}_{o(f),t}
\left(1-\delta^U_{fkt}\right),
\qquad
\forall f\in\mathcal F,\ k\in K,\ t\in T^U_f.
$$

A valid big-$M$ value is

$$
M^{\epsilon,U}_{u,t}=b^M_u-\gamma_u(t).
$$

A simpler but weaker value is

$$
M^{\epsilon,U}_{u,t}=b^M_u-a^M_u.
$$

---

### 7.6 Source unloading-before-loading constraints

The loading of the source vessel-period cannot start before its own unloading activities are completed:

$$
\tau_{o(f)}
\ge
\gamma_{o(f)}(t)+1
-
M^{\tau,U}_{o(f),t}
\left(1-\delta^U_{fkt}\right),
\qquad
\forall f\in\mathcal F,\ k\in K,\ t\in T^U_f.
$$

A valid big-$M$ value is

$$
M^{\tau,U}_{u,t}=\gamma_u(t)+1-a^M_u.
$$

A simpler but weaker value is again

$$
M^{\tau,U}_{u,t}=b^M_u-a^M_u.
$$

---

### 7.7 Destination loading-after-inbound-unloading constraints

For each flow $f$, the loading of its destination vessel-period can start only after the corresponding inbound unloading activity is completed:

$$
\tau_{d(f)}
\ge
\gamma_{d(f)}(t)+1
-
M^{\tau,D}_{d(f),t}
\left(1-\delta^U_{fkt}\right),
\qquad
\forall f\in\mathcal F,\ k\in K,\ t\in T^U_f.
$$

A valid big-$M$ value is

$$
M^{\tau,D}_{u,t}=\gamma_u(t)+1-a^M_u.
$$

This constraint is important in the wrapped-horizon setting. The same original time step $t$ must be interpreted relative to the destination vessel-period $d(f)$ through $\gamma_{d(f)}(t)$.

---

### 7.8 Loading start-time constraints

For every selected loading activity, the loading start time of vessel-period $u$ should be no later than that activity:

$$
\tau_u
\le
\gamma_u(t)
+
M^{\tau,L}_{u,t}
\left(1-\delta^L_{ukt}\right),
\qquad
\forall u\in\mathcal P,\ k\in K,\ t\in T^L_u.
$$

A valid big-$M$ value is

$$
M^{\tau,L}_{u,t}=b^M_u-\gamma_u(t).
$$

---

### 7.9 Loading end-time constraints

For every selected loading activity, the loading end time of vessel-period $u$ should be no earlier than the end of that activity:

$$
\sigma_u
\ge
\gamma_u(t)+1
-
M^{\sigma,L}_{u,t}
\left(1-\delta^L_{ukt}\right),
\qquad
\forall u\in\mathcal P,\ k\in K,\ t\in T^L_u.
$$

A valid big-$M$ value is

$$
M^{\sigma,L}_{u,t}=\gamma_u(t)+1-a^M_u.
$$

---

### 7.10 Handling interval constraints

$$
a^M_u
\le
\epsilon_u
\le
\tau_u
\le
\sigma_u
\le
b^M_u,
\qquad
\forall u\in\mathcal P.
$$

---

### 7.11 Time-deviation constraints

$$
\iota_u\ge a^E_u-\epsilon_u,
\qquad
\forall u\in\mathcal P.
$$

$$
\kappa_u\ge \sigma_u-b^E_u,
\qquad
\forall u\in\mathcal P.
$$

$$
\iota_u\ge 0,\qquad
\kappa_u\ge 0,
\qquad
\forall u\in\mathcal P.
$$

---

### 7.12 Subblock activity constraints

If flow $f$ is unloaded to subblock $k$ at time $t$, then subblock $k$ is active at $t$:

$$
\delta^U_{fkt}\le \rho_{kt},
\qquad
\forall f\in\mathcal F,\ k\in K,\ t\in T^U_f.
$$

If vessel-period $u$ loads from subblock $k$ at time $t$, then subblock $k$ is active at $t$:

$$
\delta^L_{ukt}\le \rho_{kt},
\qquad
\forall u\in\mathcal P,\ k\in K,\ t\in T^L_u.
$$

---

### 7.13 Neighboring subblock conflict constraints

Neighboring subblocks cannot be active simultaneously:

$$
\rho_{kt}+\rho_{k't}\le 1,
\qquad
\forall t\in T,\ k\in K,\ k'\in N_k.
$$

In implementation, only one constraint should be added for each unordered neighbor pair $\{k,k'\}$.

---

### 7.14 Loading lane-capacity constraints

For each passing lane and time step, loading traffic cannot exceed the loading capacity plus overload:

$$
\sum_{u\in\mathcal P:t\in T^L_u}
\sum_{k\in K}
h^L_{ukr}\delta^L_{ukt}
\le
f^L_{\max}+\theta^L,
\qquad
\forall r\in R,\ t\in T.
$$

---

### 7.15 Unloading lane-capacity constraints

For each passing lane and time step, unloading traffic cannot exceed the unloading capacity plus overload:

$$
\sum_{f\in\mathcal F:t\in T^U_f}
\sum_{k\in K}
h^U_{fkr}\delta^U_{fkt}
\le
f^U_{\max}+\theta^U,
\qquad
\forall r\in R,\ t\in T.
$$

---

## 8. Full compact model

The full compact model is

$$
\begin{aligned}
\min\quad
&
\eta
\sum_{f\in\mathcal F}\sum_{k\in K}d_{fk}w_{fk}
+
\sum_{u\in\mathcal P}
(c^E_u\iota_u+c^T_u\kappa_u)
+
P(\theta^U+\theta^L)
\$$2mm]
\text{s.t.}\quad
&
\sum_{u\in\mathcal P:t\in E_u}y_{uk}\le 1,
&&\forall k\in K,\ t\in T,
\\
&
\sum_{k\in K}y_{uk}=g_u,
&&\forall u\in\mathcal P,
\\
&
z_{fk}\le y_{d(f),k},
&&\forall f\in\mathcal F,\ k\in K,
\\
&
w_{fk}\le C_k z_{fk},
&&\forall f\in\mathcal F,\ k\in K,
\\
&
\sum_{k\in K}w_{fk}=n_f,
&&\forall f\in\mathcal F,
\\
&
\sum_{f\in\mathcal F^-(u)}w_{fk}\le C_k y_{uk},
&&\forall u\in\mathcal P,\ k\in K,
\\
&
\sum_{t\in T^U_f}\delta^U_{fkt}=z_{fk},
&&\forall f\in\mathcal F,\ k\in K,
\\
&
\sum_{t\in T^L_u}\delta^L_{ukt}=y_{uk},
&&\forall u\in\mathcal P,\ k\in K,
\\
&
\epsilon_{o(f)}
\le
\gamma_{o(f)}(t)
+
M^{\epsilon,U}_{o(f),t}
(1-\delta^U_{fkt}),
&&\forall f\in\mathcal F,\ k\in K,\ t\in T^U_f,
\\
&
\tau_{o(f)}
\ge
\gamma_{o(f)}(t)+1
-
M^{\tau,U}_{o(f),t}
(1-\delta^U_{fkt}),
&&\forall f\in\mathcal F,\ k\in K,\ t\in T^U_f,
\\
&
\tau_{d(f)}
\ge
\gamma_{d(f)}(t)+1
-
M^{\tau,D}_{d(f),t}
(1-\delta^U_{fkt}),
&&\forall f\in\mathcal F,\ k\in K,\ t\in T^U_f,
\\
&
\tau_u
\le
\gamma_u(t)
+
M^{\tau,L}_{u,t}
(1-\delta^L_{ukt}),
&&\forall u\in\mathcal P,\ k\in K,\ t\in T^L_u,
\\
&
\sigma_u
\ge
\gamma_u(t)+1
-
M^{\sigma,L}_{u,t}
(1-\delta^L_{ukt}),
&&\forall u\in\mathcal P,\ k\in K,\ t\in T^L_u,
\\
&
a^M_u
\le
\epsilon_u
\le
\tau_u
\le
\sigma_u
\le
b^M_u,
&&\forall u\in\mathcal P,
\\
&
\iota_u\ge a^E_u-\epsilon_u,
&&\forall u\in\mathcal P,
\\
&
\kappa_u\ge \sigma_u-b^E_u,
&&\forall u\in\mathcal P,
\\
&
\delta^U_{fkt}\le \rho_{kt},
&&\forall f\in\mathcal F,\ k\in K,\ t\in T^U_f,
\\
&
\delta^L_{ukt}\le \rho_{kt},
&&\forall u\in\mathcal P,\ k\in K,\ t\in T^L_u,
\\
&
\rho_{kt}+\rho_{k't}\le 1,
&&\forall t\in T,\ k\in K,\ k'\in N_k,
\\
&
\sum_{u\in\mathcal P:t\in T^L_u}
\sum_{k\in K}
h^L_{ukr}\delta^L_{ukt}
\le
f^L_{\max}+\theta^L,
&&\forall r\in R,\ t\in T,
\\
&
\sum_{f\in\mathcal F:t\in T^U_f}
\sum_{k\in K}
h^U_{fkr}\delta^U_{fkt}
\le
f^U_{\max}+\theta^U,
&&\forall r\in R,\ t\in T.
\end{aligned}
$$

with domains

$$
y_{uk},z_{fk},\delta^U_{fkt},\delta^L_{ukt},\rho_{kt}\in\{0,1\},
$$

$$
w_{fk}\ge 0,
$$

$$
\epsilon_u,\tau_u,\sigma_u,\iota_u,\kappa_u\ge 0,
$$

$$
\theta^U,\theta^L\ge 0.
$$

---

## 9. Suggested Java `ModelIndex` structure

The compact formulation is designed to map directly to array-based Java structures.

```java
final class ModelIndex {
    final int H;  // number of time steps
    final int V;  // number of vessels
    final int P;  // number of vessel-period pairs
    final int K;  // number of subblocks
    final int R;  // number of roads
    final int F;  // number of transshipment flows

    final Vessel[] vessels;
    final VesselPeriod[] vps;
    final Subblock[] subblocks;
    final Flow[] flows;

    // alpha(i,t)
    final int[][] activeVp;              // [v][t] -> u

    // flow lookup
    final int[][] flowIdByVpPair;        // [srcVp][dstVp] -> f, or -1

    // flow adjacency
    final int[][] outgoingFlowsByVp;     // [u] -> flow ids with o(f)=u
    final int[][] incomingFlowsByVp;     // [u] -> flow ids with d(f)=u

    // unloading slots
    final int[][] unloadTimesByFlow;     // [f][s] -> original time t
    final int[][] unloadRelSrcByFlow;    // [f][s] -> gamma_{o(f)}(t)
    final int[][] unloadRelDstByFlow;    // [f][s] -> gamma_{d(f)}(t)

    // loading slots
    final int[][] loadTimesByVp;         // [u][s] -> original time t
    final int[][] loadRelByVp;           // [u][s] -> gamma_u(t)

    // parameters
    final int[] g;                       // [u]
    final int[] n;                       // [f]
    final double[][] d;                  // [f][k]

    // route representation: usually better than dense h arrays
    final int[][][] routeUnload;         // [f][k] -> roads used by unloading
    final int[][][] routeLoad;           // [u][k] -> roads used by loading
}

final class Flow {
    final int id;
    final int srcVp;
    final int dstVp;
    final int srcVessel;
    final int dstVessel;
    final int containers;

    Flow(int id, int srcVp, int dstVp, int srcVessel, int dstVessel, int containers) {
        this.id = id;
        this.srcVp = srcVp;
        this.dstVp = dstVp;
        this.srcVessel = srcVessel;
        this.dstVessel = dstVessel;
        this.containers = containers;
    }
}
```

Suggested CPLEX variable arrays:

```java
IloIntVar[][] y;          // [u][k]
IloIntVar[][] z;          // [f][k]
IloNumVar[][] w;          // [f][k], or IloIntVar[][] if integer w is required

IloIntVar[][][] deltaU;   // [f][k][s], where s indexes unloadTimesByFlow[f]
IloIntVar[][][] deltaL;   // [u][k][s], where s indexes loadTimesByVp[u]

IloIntVar[][] rho;        // [k][t]

IloIntVar[] epsilon;      // [u]
IloIntVar[] tau;          // [u]
IloIntVar[] sigma;        // [u]

IloNumVar[] iota;         // [u]
IloNumVar[] kappa;        // [u]

IloNumVar thetaU;
IloNumVar thetaL;
```

---

## 10. Implementation notes

### 10.1 Eliminate $x_{ikt}$

The compact model does not create $x_{ikt}$. Whenever the original model uses $x_{ikt}$, use

$$
x_{ikt}=y_{\alpha(i,t),k}.
$$

In Java, this is

```java
int u = index.activeVp[i][t];
IloIntVar yVar = y[u][k];
```

---

### 10.2 Store $\delta^U$ by flow and local slot

Do not create

```java
deltaU[j][i][k][t]
```

for all vessel pairs and time steps. Instead, create

```java
deltaU[f][k][s]
```

only for

```java
f in flows
k in subblocks
s in unloadTimesByFlow[f]
```

where

```java
int t = index.unloadTimesByFlow[f][s];
int relSrc = index.unloadRelSrcByFlow[f][s];
int relDst = index.unloadRelDstByFlow[f][s];
```

---

### 10.3 Store $\delta^L$ by vessel-period and local slot

Similarly, create

```java
deltaL[u][k][s]
```

only for valid loading slots

```java
s in loadTimesByVp[u]
```

where

```java
int t = index.loadTimesByVp[u][s];
int rel = index.loadRelByVp[u][s];
```

---

### 10.4 Prefer route lists over dense $h$ arrays

Instead of storing dense binary arrays

```java
hU[f][k][r]
hL[u][k][r]
```

store route lists:

```java
routeUnload[f][k] = int[] roads
routeLoad[u][k] = int[] roads
```

Then, when building lane-capacity expressions, add a term only to the roads actually used by the activity.

---

### 10.5 Accumulate lane and activity expressions while creating variables

To avoid scanning all variables repeatedly, build expressions incrementally:

```java
IloLinearIntExpr[][] activityAtKT = new IloLinearIntExpr[K][H];
IloLinearIntExpr[][] unloadFlowAtRT = new IloLinearIntExpr[R][H];
IloLinearIntExpr[][] loadFlowAtRT = new IloLinearIntExpr[R][H];
```

When creating each unloading variable:

```java
int t = index.unloadTimesByFlow[f][s];
IloIntVar du = cplex.boolVar();
deltaU[f][k][s] = du;

activityAtKT[k][t].addTerm(1, du);

for (int r : index.routeUnload[f][k]) {
    unloadFlowAtRT[r][t].addTerm(1, du);
}
```

When creating each loading variable:

```java
int t = index.loadTimesByVp[u][s];
IloIntVar dl = cplex.boolVar();
deltaL[u][k][s] = dl;

activityAtKT[k][t].addTerm(1, dl);

for (int r : index.routeLoad[u][k]) {
    loadFlowAtRT[r][t].addTerm(1, dl);
}
```

Then add the activity and lane-capacity constraints from the accumulated expressions.

---

## 11. Main benefits of the compact formulation

The compact formulation reduces the model size in three ways.

First, it eliminates the original $x_{ikt}$ variables and the corresponding linking constraints.

Second, it removes the nonlinear product $\delta^U_{jkt}x_{ikt}$ by directly indexing unloading activities with transshipment flows $f$.

Third, it creates unloading variables only for valid flow-time combinations:

$$
f\in\mathcal F,\quad t\in T^U_f,
$$

rather than for all combinations

$$
j\in V,\quad i\in V,\quad k\in K,\quad t\in T.
$$

This is the main reason the compact formulation should substantially reduce Java heap usage, model build time, and CPLEX presolve memory.
