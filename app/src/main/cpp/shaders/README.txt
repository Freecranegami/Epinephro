cas_spv.h (one directory up) holds the compiled SPIR-V of these two shaders:
  glslangValidator -V cas.vert -o cas.vert.spv ; glslangValidator -V cas.frag -o cas.frag.spv
then each .spv file is written out as a uint32_t array (cas_vert_spv, cas_frag_spv).
cas.frag follows AMD FidelityFX CAS (MIT licence, Copyright (c) 2019 Advanced Micro Devices, Inc.).

sgsr_quality.frag (SGSR Quality, shares sgsr.vert):
  glslangValidator -V --target-env vulkan1.0 sgsr_quality.frag -o sgsrq.frag.spv
  python3 make_spv.py --arrays ../sgsrq_spv.h sgsrq_fs_spv=sgsrq.frag.spv
