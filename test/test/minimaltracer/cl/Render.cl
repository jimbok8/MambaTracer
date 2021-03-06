typedef struct
{
    float4 throughput;        //throughput (multiplied by emission)
    float4 hitpoint;             //position of vertex
    int    pathlength;           //path length or number of segment between source and vertex
    int    lastSpecular;
    float  lastPdfW;
    int    active;               //is path active
    BSDF   bsdf;                 //bsdf (stores local information together with incoming direction)

}Path;

void addAccum(__global float4* accum, float4 value)
{

    if(isFloat4AbsValid(value))
        atomicAddFloat4(accum, value);

}

__kernel void InitPathData(
    global Path* paths
)
{
    //get global id
    int global_id = get_global_id(0);
    //get path
    global Path* path = paths + global_id;
    //initialize path
    path->throughput          = makeFloat4(1, 1, 1, 1);
    path->lastSpecular        = true;
    path->lastPdfW            = 1;
    path->active              = true;
    path->bsdf.materialID     = -1;
    path->bsdf.frame.mX       = makeFloat4(0, 0, 0, 0);
    path->bsdf.frame.mY       = makeFloat4(0, 0, 0, 0);
    path->bsdf.frame.mZ       = makeFloat4(0, 0, 0, 0);
    path->bsdf.localDirFix    = makeFloat4(0, 0, 0, 0);
}

__kernel void LightHitPass(global Intersection* isects,
                           global Ray*          rays,
                           global Path*         paths,
                           global int*          totalLights,

                           //mesh
                           global Material*     materials,
                           global const float4* points,
                           global const float4* normals,
                           global const Face*   faces,
                           global const int*    size,

                           global float4*       accum,
                           global int*          pixel_indices,
                           global int*          num_isects)
{
    int global_id = get_global_id(0);
    TriangleMesh mesh    = {points, normals, faces, size[0]};

    if(global_id < *num_isects)
    {
       global Intersection* isect = isects + global_id;
       global Ray* ray            = rays + global_id;
       global int* index          = pixel_indices + global_id;
       global Path* path          = paths + *index;

       if(isect->hit)
       {
           //UPDATE ALL BSDF FIRST IN PATH (could have been done in another kernel but that's waste of code)
           path->bsdf                = setupBSDF(ray, isect);

           //deal with emitter
           global Material* material = materials + isect->mat;
           if(isEmitter(*material))
           {
              //light pick probability
              float lightPickProb = 1.f / *totalLights;
              //get area light and contribution
              AreaLight aLight = getAreaLight(materials, mesh, isect->id);
              float directPdfA;
              float4 contrib = getRadianceAreaLight(aLight, ray->d, isect->p, &directPdfA);
              if(isFloat3Zero(contrib.xyz))
                  return;
              
              //weight path
              float misWeight = 1.f;
              if(!path->lastSpecular)
              {
                   float directPdfW = pdfAtoW(directPdfA, ray->tMax, path->bsdf.localDirFix.z);
                   misWeight = mis2(path->lastPdfW, directPdfW * lightPickProb);
              }
             //  misWeight = 1.f;
              //accum[*index]       += contribution;
              addAccum(&accum[*index], path->throughput * contrib * misWeight);
              //we are done with this intersect and path
              isect->hit = false;
           }
       }
    }
}

__kernel void SampleBSDFRayDirection(global Intersection* isects,
                                     global Ray*          rays,
                                     global Path*         paths,  
                                     global Material*     materials,
                                     global int*          pixel_indices,
                                     global State*        state,
                                     global int*          num_rays)
{
    int global_id = get_global_id(0);

    //seeds for each thread
    int2 seed = generate_seed(state);
    //get intersection and path_index
    global Intersection* isect   = isects + global_id;
    global int* path_index       = pixel_indices + global_id;
    global Path* path            = paths + *path_index;
    global Material* material    = materials + path->bsdf.materialID;
    BSDF bsdf                    = path->bsdf;

    if(global_id < *num_rays)
    {     
        //path and ray
        global Ray* ray              = rays + global_id;

        //random sample direction
        float2 sample                = random_float2(&seed);

        //bsdf  sampling
        float4 dir;
        float pdf, cosThetaOut;
        float4 factor               = sampleBrdf(*material, bsdf, sample, &dir, &cosThetaOut, &pdf);

        //path contribution (light equation)
        path->throughput             *= factor * (cosThetaOut / pdf);

        //init new ray direction
        float4 d                     = dir;
        float4 o                     = isect->p;
        
        //set specular false for now
        path->lastSpecular           = false;
        path->lastPdfW               = pdf;

        //new ray direction
        initGlobalRay(ray, o, d);
        InitIsect(isect);
    }
}

__kernel void DirectLight(
    global Path*         paths,
    global Intersection* isects,
    global Light*        lights,
    global int*          totalLights,
    global Ray*          occlusRays,

    //accumulation
    global float4*       accum,
    global int*          pixel_indices,
    
    //count
    global int*          activeCount,
    global State*        state,

    //mesh and material
    global Material*     materials,
    global const float4* points,
    global const float4* normals,
    global const Face*   faces,
    global const int*    size,
    
    //bvh
    global const BVHNode* nodes,
    global const BoundingBox* bounds
)
{

    int global_id        = get_global_id(0);
    TriangleMesh mesh    = {points, normals, faces, size[0]};
    
    //get intersection and path_index
    global Intersection* isect   = isects + global_id;
    global Ray* ray              = occlusRays + global_id;
    global int* pixel_index       = pixel_indices + global_id;
    global Path* path            = paths + *pixel_index;

    if(global_id < *activeCount)
    {        
        //seeds for each thread
        int2 seed = generate_seed(state);

        //for light surface sample
        float2 sample                = random_float2(&seed);
         //sample light index uniformly
        int lightIndex = random_int_range(&seed, *totalLights);
        //light and index of mesh
        global Light* light = lights + lightIndex;
        int triangleIndex   = light->faceId;
        //light pick probability
        float lightPickProb = 1.f / *totalLights;
        //get area light
        AreaLight aLight = getAreaLight(materials, mesh, triangleIndex);
        //material
        global Material* material    = materials + getMaterial(isect->mat);
        //radiance from direct light
        float4 directionToLight = makeFloat4(0, 0, 0, 0);
        float distance, directPdfW;
        float4 radiance = illuminateAreaLight(aLight, isect->p, sample, &directionToLight, &distance, &directPdfW);

        if(!isFloat3Zero(radiance.xyz))
        {
            float bsdfPdfW, cosThetaOut;
            float4 factor =  evaluateBrdf(*material, path->bsdf, directionToLight, &cosThetaOut, &bsdfPdfW);

            if(!isFloat3Zero(factor.xyz))
            {
                float4 contrib = makeFloat4(0, 0, 0, 0);    //important since undeclared variable might have issues when used
                float weight = 1.f;
                //weight = mis2(directPdfW * lightPickProb, bsdfPdfW);
                weight = mis2(directPdfW, bsdfPdfW);

                contrib.xyz    = (weight * cosThetaOut / (lightPickProb * directPdfW)) *
                                 (radiance.xyz * factor.xyz)* path->throughput.xyz;
                //new ray direction
                initGlobalRayT(ray, isect->p, directionToLight, distance - 2*EPS_RAY);

                //test occlusion
                if(!testOcclusion(ray, mesh, nodes, bounds))
                {
                    addAccum(&accum[*pixel_index], contrib);
                }
            }
        }
    }
}

__kernel void UpdateImage(global float4*       accum,
                          global float*        frameCount,
                          global int*          imageBuffer)
{
    //get thread id
    int id                     = get_global_id( 0 );
    
    global float4* accumAt     = accum + id;
    global int*    rgbAt       = imageBuffer + id;
    
    float4 color               = (float4)((*accumAt).xyz/frameCount[0], 1);
    color.xyz = pow(color.xyz, (float3)(1.f/1.5f));

    *rgbAt                     = getIntARGB(color);
}




