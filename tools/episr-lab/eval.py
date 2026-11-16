import sys, numpy as np, cv2, lab
def load(names):
    return [lab.to_f(cv2.cvtColor(cv2.imread(f'shots/{n}.png'),cv2.COLOR_BGR2RGB)) for n in names]
def tex(o,g):
    go,gg=lab.grad_mag(o),lab.grad_mag(g); t=(gg>0.01)&(gg<0.08); return go[t].sum()/gg[t].sum()
import os
DOWN=os.environ.get('DOWN','area')
def run(fn,gts,scales,**kw):
    res={}
    for sc in scales:
        rows=[]
        for g in gts:
            h,w=g.shape[:2]; s=lab.render_small(g,sc,DOWN)
            o=np.asarray(fn(s,w,h,**kw),np.float32); m=lab.metrics(o,g); rows.append([m['psnr'],m['ssim'],m['sharp'],m['ring'],tex(o,g)])
        res[sc]=np.array(rows)
    return res
if __name__=='__main__':
    names=sys.argv[1].split(','); scales=[float(x) for x in sys.argv[2].split(',')]; gts=load(names)
    for spec in eval(sys.argv[3]):
        name,kw=spec; fn=getattr(lab,name,None) or lab.UPSCALERS[name]; r=run(fn,gts,scales,**kw)
        print(name,kw)
        for sc,a in r.items():
            print('   x%.2f mean psnr %.2f ssim %.4f edge %.3f ring %.3f tex %.3f | per-image psnr %s'%((1/sc,)+tuple(a.mean(0))+(' '.join('%.2f'%v for v in a[:,0]),)),flush=True)
